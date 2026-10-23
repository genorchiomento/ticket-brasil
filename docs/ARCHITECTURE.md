# Arquitetura e decisões

Por que o Ticket Brasil é construído como é: as garantias que o sistema oferece, como cada uma é obtida
e as alternativas descartadas. Visão geral e quick start no [README](../README.md).
## 1. Nunca oversell: decremento condicional atômico no banco

```sql
UPDATE events SET available = available - :q WHERE id = :id AND available >= :q
```

Uma única instrução. O PostgreSQL trava a linha e, se outra transação a alterou nesse meio-tempo,
**reavalia o `WHERE` contra a versão commitada mais recente** (EvalPlanQual em READ COMMITTED).
0 linhas afetadas = sem estoque → 409. Funciona com qualquer número de instâncias, pois
a coordenação está no banco, não na JVM.

Defesa em profundidade: `CHECK (available >= 0 AND available <= capacity)`. Mesmo um bug futuro
não consegue commitar oversell.

**Alternativas descartadas**

| Opção | Por que não |
|---|---|
| `SELECT` + verificação em código + `UPDATE` | Race clássico (check-then-act) sem lock explícito. |
| `SELECT ... FOR UPDATE` | Correto, mas 2 round-trips segurando o lock; o `UPDATE` condicional faz o mesmo em 1. |
| Lock otimista (`version`) | Em flash sale todo mundo disputa a mesma linha → tempestade de retries. |
| Lock distribuído (Redis/Redlock) | Mais uma peça para falhar; o banco já oferece a garantia, e transacional com a reserva. |
| Redis `DECRBY` como fonte da verdade | Rápido, mas exige reconciliar dois sistemas (reserva no PG, estoque no Redis) sem transação comum. |

## 2. Idempotência: a chave é uma constraint, não um cache

`POST /events/{id}/reservations` exige `Idempotency-Key`. Dentro de **uma** transação:

1. `INSERT ... ON CONFLICT (idempotency_key) DO NOTHING`
2. Se não inseriu → a chave já existe: compara o *fingerprint* (SHA-256 de `eventId|customerId|quantity`).
   Igual → devolve a reserva original (`201`, `Idempotent-Replayed: true`). Diferente → `422 IDEMPOTENCY_KEY_REUSED`.
3. Se inseriu → decrementa o estoque. Sem estoque → exceção → **rollback do INSERT** também.

Por que é correto sob concorrência: se duas requisições com a mesma chave chegam juntas (em instâncias
diferentes), o `INSERT` da segunda **bloqueia** no índice único até a primeira terminar; depois ou insere
(primeira deu rollback) ou não faz nada (primeira commitou). Duplicata é impossível — provado em teste
com 30 retries simultâneos.

Consequência deliberada: uma tentativa que falhou por falta de estoque **não queima** a chave. O cliente pode
repetir a mesma chave depois e ter sucesso se assentos voltarem (cancelamento/expiração).

`DELETE` e `confirm` são naturalmente idempotentes (transições para o mesmo estado retornam 200).
`POST /events` aceita a chave opcionalmente, mesma mecânica.

## 3. Expiração automática: sweeper com `FOR UPDATE SKIP LOCKED`

Reservas nascem `PENDING` com `expires_at = now + TTL` (10 min, configurável). Cada instância roda um
sweeper (`@Scheduled`, 5s) que, em lotes de 500:

```sql
SELECT ... FROM reservations WHERE status = 'PENDING' AND expires_at <= :now
ORDER BY expires_at LIMIT 500 FOR UPDATE SKIP LOCKED
```

marca `EXPIRED` e devolve os assentos — tudo na mesma transação.

- **Multi-instância sem líder**: `SKIP LOCKED` faz cada instância pegar um lote disjunto. Sem ShedLock,
  sem eleição de líder, sem devolução dupla (teste com 6 sweepers concorrentes).
- **Corrida confirm × expiração**: `confirm`/`cancel` travam a linha (`FOR UPDATE`). O sweeper pula linhas
  travadas; quem pega o lock primeiro decide, o outro vê o estado final.
- **Sem janela de inconsistência na leitura**: entre o deadline e o próximo sweep, a linha ainda é `PENDING`
  no banco, mas a API expõe o **status efetivo** (`EXPIRED`) e recusa `confirm`. O sweeper é só quem devolve os assentos.
- Índice parcial `WHERE status = 'PENDING'` mantém a varredura barata independente do histórico.
- Ordem de locks sempre reserva → evento (e eventos ordenados por id no sweeper) → sem deadlock.

Por que não fila com delay (Redis keyspace events, SQS delay, RabbitMQ DLX)? Mais infraestrutura e
"at-least-once" exigiria a mesma idempotência no consumidor. A tabela já é a fila; o atraso máximo de
devolução (intervalo do sweep) é aceitável para estoque.

## 4. Consistência eventual na disponibilidade

`GET /events/{id}` é o endpoint mais martelado num flash sale. Cada instância mantém um cache local
(Caffeine) com TTL de 1s + `Cache-Control: max-age=1`: N requisições/s viram ~1 query por evento por
segundo por instância — misses simultâneos da mesma chave compartilham uma única query (sem *stampede*
quando a entrada expira). O valor pode estar até 1s defasado — **e isso nunca afeta a correção**, porque a
decisão de reservar é sempre tomada contra a linha do banco. Escritas locais invalidam o cache da
instância (read-your-writes no mesmo nó); a invalidação espera um carregamento em andamento da mesma
chave, então um valor lido antes da escrita nunca fica no cache depois dela.

Sem Redis de propósito: cache local não tem invalidação distribuída a coordenar e o TTL curto já
limita a defasagem.

## 5. Tratamento explícito de erros

- Hierarquia `sealed class DomainException`: o `when` no handler é exaustivo — um novo erro de domínio
  sem mapeamento HTTP **não compila**.
- Validação de borda (Bean Validation → `VALIDATION_ERROR` com lista de campos) separada de regras de
  negócio (no serviço, que conhece o `Clock`).
- Erro inesperado: log com stack trace, resposta genérica `INTERNAL_ERROR` sem vazar internals.

## 6. Outras decisões

- **JDBC (`JdbcClient`) em vez de JPA**: o núcleo do problema são 3–4 SQLs cuja semântica exata
  (`ON CONFLICT`, `SKIP LOCKED`, `UPDATE` condicional) é a solução. Escondê-los atrás de um ORM só
  atrapalharia o review.
- **`TransactionTemplate` explícito em vez de `@Transactional`**: fronteiras de transação visíveis no código,
  sem armadilhas de proxy/self-invocation.
- **`Clock` injetado** (resolução de microssegundos = a do `TIMESTAMPTZ`; `startsAt` também é truncado):
  testes de expiração determinísticos e a resposta de um replay idêntica à original.
- **Flyway**: migrações seguras com várias instâncias subindo juntas (lock no banco).
- **nginx**: por padrão o LB nunca reenvia a outro nó um POST que já chegou a um upstream (exigiria
  `non_idempotent`); retry é responsabilidade do cliente, com a mesma `Idempotency-Key`. Conexões com
  as instâncias são reaproveitadas (`keepalive`), sem um handshake TCP por requisição.
- **Sem senha padrão**: `DB_PASSWORD` é obrigatória fora do compose/testes.

## Um bug achado no caminho

No teste de carga via compose apareceu **1 HTTP 500 em 1000**: sob concorrência na partida a frio, o
Jackson 3.1 falhava ao serializar o `ProblemDetail` do Spring (NPE resolvendo o key serializer do
`Map` exposto via `@JsonAnyGetter`). A resposta era um 409 legítimo que virava 500. Troquei por um DTO
tipado (`ApiError`) com o mesmo formato RFC 9457; 4.500 requisições depois, zero 500.

## Trade-offs e próximos passos

- **Linha quente**: todas as reservas de um evento serializam no lock da mesma linha. Cada transação
  segura o lock por poucos ms (estimativa: ordem de centenas a milhares de reservas/s por evento, a medir com carga real).
  Para escalar além: *sharding* do estoque em N linhas-balde (`event_inventory(event_id, bucket, available)`,
  escolhendo balde aleatório), ou um *gate* em Redis (`DECRBY` atômico) que filtra a multidão antes do banco,
  mantendo o PG como fonte da verdade.
- **Rejeição antecipada**: com o evento esgotado, ainda se paga um round-trip ao banco por tentativa. Um
  atalho via cache ("esgotado") é possível, mas precisa consultar a chave de idempotência antes para não
  negar um replay legítimo.
- **Relógio**: `expires_at` vem do relógio da aplicação; assume NTP entre instâncias (desvio de ms é
  irrelevante frente a um TTL de minutos).
- **Fila virtual / rate limit por cliente**, autenticação, limite de ingressos por CPF, observabilidade
  (métricas de reservas/s, 409/s, lag do sweeper) e expiração das chaves de idempotência antigas ficaram
  fora do escopo.
