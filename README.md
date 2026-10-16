# Basic User API — Spring

Primeira etapa da API inspirada em [basic-user-api](https://github.com/patrickgpcw/basic-user-api): cadastro, login JWT e consulta do próprio usuário. Java 25, Spring Boot e Gradle Wrapper.

## Executar com Docker

Requisito: Docker com Compose. O build da imagem inclui o JDK 25 e o Gradle Wrapper; não é necessário instalar Java na máquina.

```sh
cp .env.example .env
# Edite .env: defina DB_PASSWORD e JWT_SECRET (openssl rand -hex 32).
docker compose up -d --build --wait
```

O Compose lê `.env` automaticamente e sobe dois serviços: `app` (Spring Boot) e `postgres` (PostgreSQL 17 Alpine). A aplicação aguarda o healthcheck do banco e se conecta pelo hostname `postgres`. O Dockerfile usa build em múltiplas etapas e executa a aplicação com usuário sem privilégios de root. Segredos não são copiados para a imagem.

- API: http://localhost:8080
- Swagger UI: http://localhost:8080/swagger-ui/index.html
- OpenAPI JSON: http://localhost:8080/v3/api-docs
- Healthcheck: http://localhost:8080/actuator/health

`APP_PORT` e `DB_PORT` permitem alterar as portas locais (padrões 8080 e 5432). Os dados do banco ficam em volume persistente. Flyway cria as tabelas e Hibernate valida o schema.

```sh
docker compose logs -f app
docker compose ps
docker compose down
```

`down` preserva os dados. Alterar a senha no `.env` não altera a senha de um banco já inicializado no volume. Nunca versione `.env`.

## Executar a aplicação fora do Docker

Com JDK 25 instalado, suba apenas o banco e exporte as variáveis antes de iniciar o Spring:

```sh
docker compose up -d --wait postgres
set -a
source .env
set +a
./gradlew bootRun
```

O Gradle não carrega `.env` automaticamente. `DB_URL` pode substituir a URL padrão `jdbc:postgresql://localhost:5432/basic_user_api`; ajuste-a se usar outra porta no banco. Pare o serviço `app` com `docker compose stop app` antes de iniciar a aplicação localmente na mesma porta.

## Swagger

A documentação usa [springdoc-openapi](https://springdoc.org/getting-started.html). Abra o Swagger UI, cadastre um usuário em `POST /api/users` e execute `POST /api/login`. Copie `accessToken`, clique em **Authorize** e cole somente o token, sem o prefixo `Bearer`. Depois execute `GET /api/users/me`.

Cadastro e login são públicos. A documentação indica o cadeado apenas no endpoint que requer JWT. Swagger e healthcheck estão liberados para acesso sem token; as demais rotas continuam protegidas.

## Endpoints

| Método | Endpoint | Acesso |
| --- | --- | --- |
| POST | `/api/users` | Público: cadastro |
| POST | `/api/login` | Público: login |
| POST | `/api/refresh` | Refresh token: renovar tokens |
| POST | `/api/logout` | JWT: encerrar a sessão atual |
| GET | `/api/users/me` | JWT: usuário autenticado |
| PUT | `/api/users/me` | JWT: atualizar o próprio perfil |
| DELETE | `/api/users/me` | JWT: excluir a própria conta |

Cadastro:

```sh
curl -i http://localhost:8080/api/users \
  -H 'Content-Type: application/json' \
  -d '{"firstName":"Patrick","lastName":"Pessanha","email":"patrick@example.com","telephone":"+5511999999999","password":"password123"}'
```

Login:

```sh
curl http://localhost:8080/api/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"patrick@example.com","password":"password123"}'
```

Use o `accessToken` retornado:

```sh
curl http://localhost:8080/api/users/me -H 'Authorization: Bearer SEU_TOKEN'
```

O access token expira em uma hora. Cada login cria uma sessão com refresh token válido por até sete dias, com rotação a cada uso. O logout revoga imediatamente todos os tokens da sessão atual. DTOs de saída não incluem senha nem hash; entradas são validadas e senhas usam PBKDF2 com salt. E-mails são normalizados para minúsculas e têm restrição única no banco. A autenticação das rotas protegidas usa o header Bearer. A API não usa cookies nem HttpSession; mantém sessões de autenticação no banco para revogação.

Respostas: `201` cadastro, `200` login/consulta, `400` validação, `401` credenciais/token inválidos e `409` e-mail duplicado. Erros de validação e negócio usam Problem Details.

## Testes

```sh
./gradlew test
```

A suíte usa JUnit Jupiter, Mockito e AssertJ:

- `AuthServiceTest`: testes unitários com repositório, encoder de senha e emissor JWT simulados pelo Mockito. Verificam cadastro, normalização, duplicidade, credenciais inválidas, claims e duração do token, além da consulta do usuário.
- `AuthIntegrationTests`: testes HTTP com MockMvc, validação dos DTOs, persistência, proteção das respostas, isolamento entre usuários e rejeição de tokens inválidos, expirados, adulterados ou de outro emissor.
- `OpenApiIntegrationTests`: acesso público ao Swagger e healthcheck, configuração Bearer e proteção dos endpoints.
- `BasicUserApiSpringApplicationTests`: inicialização do contexto Spring.

Para executar apenas os testes unitários:

```sh
./gradlew test --tests '*AuthServiceTest'
```

O relatório HTML fica em `build/reports/tests/test/index.html`.

Os testes de integração ativam explicitamente o profile `test`, com H2 em memória no modo PostgreSQL, executando as mesmas migrations. Não precisam de Docker nem de variáveis locais. H2 é dependência exclusiva de testes. A suíte `postgresTest` valida os comportamentos específicos do PostgreSQL no banco real.

## Atualizar ou excluir a própria conta

`PUT /api/users/me` substitui o perfil. Envie `firstName`, `lastName` e `email`; `telephone` é opcional e será limpo quando omitido. A senha não é alterada por essa operação. O e-mail é normalizado e não pode pertencer a outra conta.

```sh
curl -X PUT http://localhost:8080/api/users/me \
  -H 'Authorization: Bearer SEU_TOKEN' -H 'Content-Type: application/json' \
  -d '{"firstName":"Patrick","lastName":"Pessanha","email":"patrick@example.com","telephone":"+5511999999999"}'

curl -X DELETE http://localhost:8080/api/users/me -H 'Authorization: Bearer SEU_TOKEN'
```

A exclusão é definitiva e retorna `204` sem corpo. O JWT identifica a conta em todas essas operações; não existe rota de alteração/exclusão por ID. Após excluir a conta, o token anterior recebe `401` nas operações de perfil.

Exceptions customizadas retornam Problem Details com um campo `code`: `EMAIL_ALREADY_REGISTERED` (409), `INVALID_CREDENTIALS` (401), `CURRENT_USER_UNAVAILABLE` (401). Validações retornam `VALIDATION_ERROR` (400) e a lista de campos inválidos; conflitos detectados pelo banco retornam `USER_CONFLICT` (409), sem expor SQL.

`UserProfileIntegrationTests` cobre atualização, exclusão, validação, duplicidade de e-mail, preservação da senha, isolamento entre contas e rejeição do token após exclusão.

## Refresh token e logout

O login retorna `accessToken`, `tokenType`, `expiresIn`, `refreshToken` e `refreshExpiresIn` (durações em segundos). Guarde os tokens como segredos. A renovação não exige access token; envie apenas o refresh token no corpo, inclusive quando o access token já expirou:

```sh
curl -X POST http://localhost:8080/api/refresh \
  -H 'Content-Type: application/json' \
  -d '{"refreshToken":"SEU_REFRESH_TOKEN"}'

curl -X POST http://localhost:8080/api/logout \
  -H 'Authorization: Bearer SEU_ACCESS_TOKEN'
```

Substitua ambos os tokens pelos novos valores após cada renovação. O refresh anterior deixa de funcionar; duas renovações simultâneas com o mesmo token produzem apenas um sucesso. O prazo da sessão é absoluto (sete dias desde o login), não é estendido pela renovação. `security.jwt.ttl` e `security.refresh.ttl` configuram as durações.

O refresh token contém um identificador de sessão e 32 bytes aleatórios. Somente seu hash SHA-256 é persistido. Tokens inválidos, expirados, revogados ou já utilizados recebem `401` com `code: INVALID_REFRESH_TOKEN`. Reutilização rejeita o token antigo, sem revogar automaticamente o substituto válido.

Logout retorna `204` e afeta apenas a sessão indicada pelo JWT, preservando logins de outros dispositivos. Tokens de acesso anteriores da mesma sessão também deixam de funcionar. Se o access token expirou, renove antes de fazer logout. A exclusão da conta remove suas sessões por cascade. A validação de cada JWT consulta o banco, portanto requer disponibilidade do PostgreSQL.

**Compatibilidade:** JWTs emitidos antes desta alteração não têm sessão associada e serão recusados. Faça login novamente após atualizar. Erros de sessão no filtro de segurança retornam `401` pelo mecanismo Bearer do Spring; erros de refresh usam a exception customizada. Sessões expiradas/revogadas permanecem no banco até a exclusão da conta; limpeza periódica ainda não está implementada.

`SessionIntegrationTests` cobre rotação, concorrência, expiração, logout, isolamento entre sessões e exclusão da conta. `AuthSessionServiceTest` valida os claims, os prazos e o armazenamento do hash.

## Proteção contra tentativas de login

`POST /api/login` permite por padrão 30 requisições por IP e 5 tentativas por e-mail em uma janela fixa de um minuto, iniciada na primeira tentativa. A comparação de e-mail ignora maiúsculas e espaços nas extremidades. Contas existentes e inexistentes seguem a mesma regra, sem consulta prévia ao cadastro.

Todas as tentativas contam, inclusive logins bem-sucedidos. JSON malformado e falhas de validação também consomem o limite por IP. O limite por e-mail é aplicado após validar o DTO e antes de verificar a senha. Requisições bloqueadas não prolongam a janela que as bloqueou.

Ao atingir um limite, a API retorna `429`, o header `Retry-After` em segundos e Problem Details com `code: LOGIN_RATE_LIMITED` e `retryAfterSeconds`. O cliente deve aguardar antes de tentar novamente.

| Variável | Padrão | Descrição |
| --- | --- | --- |
| `LOGIN_IP_LIMIT` | 30 | Requisições por IP em cada janela |
| `LOGIN_ACCOUNT_LIMIT` | 5 | Tentativas por e-mail em cada janela |
| `LOGIN_WINDOW` | `PT1M` | Duração ISO-8601 da janela (mínimo de um segundo) |
| `LOGIN_MAX_ENTRIES` | 10000 | Capacidade total de contadores de IP e conta |

O Docker Compose encaminha essas variáveis do `.env`. Os contadores são sincronizados e ficam em memória, por instância, sendo reiniciados com a aplicação. Para múltiplas réplicas será necessário um armazenamento compartilhado, como Redis. Ao atingir a capacidade, entradas expiradas são removidas; novas origens são bloqueadas temporariamente quando só restam contadores ativos, sem descartar bloqueios existentes.

O IP vem da conexão (`remoteAddr`); `X-Forwarded-For` e `Forwarded` não são confiados (`server.forward-headers-strategy=none`). Atrás de proxy ou NAT, clientes podem compartilhar o mesmo limite. Antes de usar proxy reverso, configure explicitamente uma política de proxies confiáveis. O limite por e-mail também pode temporariamente afetar o titular de uma conta alvo de tentativas de terceiros.

A proteção é restrita ao login; cadastro e refresh não ganham limites nesta etapa. `LoginRateLimiterTest` cobre expiração sem sleeps, concorrência, normalização e capacidade. `LoginRateLimitIntegrationTests` valida HTTP 429, headers, JSON inválido, isolamento e verificação da senha apenas dentro do limite.

## Testes com PostgreSQL e Testcontainers

A suíte separada `src/postgresTest` usa [Testcontainers](https://java.testcontainers.org/modules/databases/postgres/) para iniciar `postgres:17-alpine`, a mesma versão principal do Compose. Requer Docker ativo e acesso ao registry para baixar imagens na primeira execução.

```sh
# Testes rápidos com H2 e mocks: não precisam de Docker
./gradlew test

# Testes de integração com PostgreSQL real
./gradlew postgresTest

# Executar as duas suítes
./gradlew test postgresTest
```

`postgresTest` usa porta aleatória, credenciais exclusivas de teste e banco descartável. Não lê `.env`, não usa o volume do Compose e pode rodar junto com o ambiente de desenvolvimento. Testcontainers inicia e remove o container automaticamente. H2 é excluído do classpath dessa suíte; as propriedades da conexão vêm do container via `DynamicPropertySource`.

O profile `postgres-test` mantém Flyway habilitado e `ddl-auto=validate`: são executadas as migrations reais da aplicação, sem criação automática de schema pelo Hibernate. Os testes validam banco/versão, migrations, unicidade do e-mail, chave estrangeira, cascade de exclusão das sessões, UUID, precisão do timestamp, fluxo HTTP e renovação concorrente do refresh token.

Relatório: `build/reports/tests/postgresTest/index.html`. Se Docker estiver indisponível, a suíte falha em vez de ignorar testes. `test`, `check` e `build` continuam sem exigir Docker; execute `postgresTest` explicitamente para a validação completa. O CI executa explicitamente as duas suítes a cada push e pull request para main.

## CI — GitHub Actions

O workflow `.github/workflows/ci.yml` executa em pushes, pull requests para `main` e manualmente pela aba Actions (após estar na branch padrão).

1. **Tests and JAR**: runner Ubuntu 24.04, Temurin Java 25 e cache das dependências Gradle. Executa `./gradlew test postgresTest bootJar --no-daemon --stacktrace --continue`. Testcontainers usa o Docker do runner para subir o PostgreSQL descartável, sem precisar de secrets ou serviços de banco configurados no GitHub.
2. **Docker image build**: após sucesso do primeiro job, constrói o Dockerfile completo. Não envia a imagem para registry e não realiza deploy.

Relatórios HTML e XML ficam no artefato `test-reports`, inclusive quando os testes falham. O JAR é disponibilizado como `application-jar` apenas quando o job passa. Os artefatos são retidos por sete dias. Jobs têm timeout de 20 minutos e novas execuções cancelam a anterior da mesma referência Git. Push e PR têm referências distintas e podem gerar duas execuções para a mesma branch.

As actions oficiais estão fixadas por SHA e o token do workflow tem apenas `contents: read`. A execução não usa `.env` nem credenciais do ambiente de desenvolvimento. O CI testa Linux x64; o teste local em macOS não substitui a primeira execução no GitHub.

Para exigir CI antes do merge, configure uma regra de proteção da `main` com os checks **Tests and JAR** e **Docker image build**. A inclusão do workflow por si só não modifica regras de proteção do repositório.
