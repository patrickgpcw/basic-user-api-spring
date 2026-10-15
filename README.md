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

O token expira em uma hora. Nesta etapa não há refresh ou revogação de tokens. DTOs de saída não incluem senha nem hash; entradas são validadas e senhas usam PBKDF2 com salt. E-mails são normalizados para minúsculas e têm restrição única no banco. A autenticação usa exclusivamente o header Bearer e não cria sessão/cookie.

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

Os testes de integração ativam explicitamente o profile `test`, com H2 em memória no modo PostgreSQL, executando as mesmas migrations. Não precisam de Docker nem de variáveis locais. H2 é dependência exclusiva de testes; diferenças específicas do PostgreSQL devem ser validadas no banco real.

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
