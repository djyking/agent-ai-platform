# Phase 4 identity and model adapter boundaries

The product layer no longer chooses an Ops project before the user authenticates. `POST /console/login` and `POST /console/session` accept an omitted or blank `projectId`. The server tests the authenticated user's access against configured projects, chooses the first authorized project, and returns only authorized project IDs. An explicitly supplied project never silently falls back. Upstream outages and rate limits stop discovery. The browser still receives only an HttpOnly session cookie and CSRF token; application credentials and identity tokens remain server-side.

`GET /console/auth/config` identifies the selected login adapter using `provider`, `localTestOnly`, and `captchaRequired`. It discloses no project list, account list, endpoint, or secret.

## Existing identity remains the default

`HARNESS_IDENTITY_PROVIDER=opsagent` (also the omitted default) uses the existing `identityOrigin`, application secret references, Ops login/captcha, and delegated identity protocol. It does not create a second production account database. Business output authorization is an independent `ProtectedOutputPolicy`; the Ops adapter retains exact, untransformed retrieval provenance and a fresh citation ACL check. An unrecognized protected output has no permissive fallback. A domain integration must provide a policy that recognizes its own provenance and checks current authorization.

## Independent local acceptance identity

The `local-test` adapter is only for an isolated, loopback development instance. It requires all of:

```text
HARNESS_IDENTITY_PROVIDER=local-test
HARNESS_ALLOW_LOCAL_TEST_IDENTITY=true
server.address=127.0.0.1
HARNESS_LOCAL_IDENTITY_CONFIG=<absolute path under ignored .work to local users.json>
HARNESS_LOCAL_IDENTITY_STATE=<absolute path under ignored .work to delegation state directory>
```

The Spring property `server.address` can also be provided by `SERVER_ADDRESS`; IPv6 literal loopback is supported. Wildcard binding, remote binding, missing explicit opt-in, and an unknown adapter are rejected. Do not put this instance behind a public proxy or publish its port. Keep the config, secret files, signing key, and delegation state restricted to the local operator. This fixture provides no production registration, password recovery, account management or external business impersonation.

The trusted config contains only secret references and explicit grants, for example:

```json
{
  "users": [
    {
      "username": "local-author",
      "subject": "local-author",
      "secretRef": "env:HARNESS_LOCAL_AUTHOR_PASSWORD",
      "grants": [
        {
          "application": "platform-console",
          "project": "studio",
          "permissions": ["runs:create", "runs:read", "runs:list", "runs:output:read", "runs:events:read", "runs:control", "model:invoke", "catalog:read", "catalog:write", "catalog:validate", "catalog:publish"]
        }
      ]
    }
  ]
}
```

The project and application must exist in the deployment manifest. Use a generated password of at least 16 characters through an environment variable or protected secret file, and unique application credentials. Tool permissions must be granted explicitly for the selected capabilities; wildcard permissions are rejected. The login page labels this mode as local acceptance and does not request an Ops captcha.

The [standalone deployment example](../deploy/platform/phase4-deployment.example.json) provides `studio-dev`, the console application, and two approved model anchors without an Ops tool. Match the identity grant project to `studio-dev` and add `tool:studio:knowledge` for knowledge applications. Supply the environment secret references, a dedicated database and the opt-in local identity paths; the model IDs are examples verified at acceptance time, not aliases that will be rewritten automatically. Keep the normal Ops identity setting for existing installations.

Session identity expires after 30 minutes. Run delegation receipts are bound to subject, application, project, Run ID, expiry and a credential version. They survive ordinary process restart when the same signing key and state directory are retained. Password rotation, account removal, permission removal and malformed authority configuration fail closed on the next check. Local identity cannot mint an Ops or other external business token. Public/synthetic tools and an independently authorized knowledge policy can be used without an Ops system.

## Compatible chat-completions models

`ChatCompletionsModel` is the common non-streaming text/function-tool transport. The existing `DeepSeekChatModel` remains a compatibility wrapper: `max_tokens` and explicit `thinking.type=disabled` are unchanged. Generic compatible providers do not receive that DeepSeek-specific field.

Trusted generic bindings must specify their endpoint, credential reference and the output-token parameter understood by the endpoint. `max_completion_tokens` is the current OpenAI Chat Completions parameter; `max_tokens` remains an explicit option for other compatible endpoints. These are alternative fields, never sent together. The displayed provider name is not used to guess an endpoint or protocol option.

Source verified on 2026-09-18: [OpenAI Chat Completions create reference](https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create). OpenAI recommends Responses for new OpenAI-native applications; this adapter intentionally implements the shared Chat Completions contract for platform portability, not all Responses features or every provider/model combination.

The adapter has no implicit retry or redirect. Transport failures and server failures that may have consumed tokens remain `UNKNOWN`. Requests are bounded by the run deadline, model timeout and configured body limit. Provider error bodies do not enter public errors. Validation covers local protocol fixtures and preservation of the DeepSeek adapter's tests.

On 2026-09-18, live acceptance completed with DeepSeek `deepseek-v4-pro` / `deepseek-flash` and OpenAI `gpt-4.1-mini`. The OpenAI path used `max_completion_tokens` for an independent Studio application, real preview, frozen-sample evaluation and publication. See the [live acceptance evidence](validation/20260918/phase4-openai-acceptance.json). This establishes the tested bindings only; Kimi and other compatible models/providers remain unverified.

The first OpenAI attempt stayed `UNKNOWN` and was not replayed. Python respected the existing Windows HTTP proxy while the original Java process did not. A credential-free Java `/v1/models` probe reproduced the direct connect timeout and returned HTTP 401 with the existing local proxy. Explicit `https.proxyHost`, `https.proxyPort` and `http.nonProxyHosts` JVM options fixed the isolated acceptance process; localhost and DeepSeek remained direct. No system proxy settings or adapter retry behavior were changed. Deployments must explicitly configure their trusted network route; directory diagnostics alone do not prove generation succeeds.
