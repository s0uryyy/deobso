# Ely.by: браузерный вход (Device Authorization)

## Использование

1. В меню аккаунтов выбери Add → Ely.by.
2. Откроется окно в том же оформлении, что Microsoft-авторизация: затемнённый фон,
   центральная рамка, заголовок, статус и Back. Мод получает одноразовый код.
3. Браузер автоматически откроет **https://account.ely.by/code?user_code=…**.
4. Войди на сайте, пройди 2FA и подтверди разрешения для приложения **deobso**.
5. Вернись в игру: мод дождётся подтверждения и добавит аккаунт.

Если браузер не открылся, используй «Открыть браузер» или «Копировать код» и
вручную открой https://account.ely.by/code. Никому не передавай этот код.
«Назад» отменяет ожидание; «Повторить» создаёт новый запрос после ошибки.
Пароль и 2FA никогда не вводятся в моде.

## Почему заменён прежний PKCE-вход

В просмотренной реализации сайта Ely.by `InitOAuthAuthCodeFlowState` извлекает
`code_challenge` / `code_challenge_method`, но `getOAuthRequest` не включает их в
запрос `/validate`. Публичный клиент требует PKCE, а обработчик ошибок сайта может
показывать `Invalid request (null required)` вместо имени недостающего параметра.

Текущий пользовательский вход использует поддерживаемый Ely.by **OAuth 2.0 Device
Authorization Grant (RFC 8628)**, как устройство-код в Microsoft. Это другой
стандартный grant для публичных клиентов: секрет приложения не встраивается,
а требование PKCE для Authorization Code не отключается. Браузер передаёт только
user_code; приватный device_code остаётся в моде. Локальный HTTP-callback больше
не используется. Старые классы PKCE/callback оставлены для совместимости тестов,
но экран авторизации их не запускает.

## Протокол

- Публичный Client ID: `deobso`.
- Scopes: `account_info minecraft_server_session offline_access`; e-mail не запрашивается.
- Инициализация: POST `https://account.ely.by/api/oauth2/v1/devicecode`.
- Опрос: POST `https://account.ely.by/api/oauth2/v1/token`,
  grant_type `urn:ietf:params:oauth:grant-type:device_code`.
- `authorization_pending` продолжает ожидание. `slow_down` увеличивает интервал
  на 5 секунд. При транспортных ошибках опрос замедляется; срок действия кода
  ограничивает общее ожидание. `access_denied`, `expired_token`, `invalid_client`
  и другие терминальные ошибки прекращают вход.
- Ely.by фактически возвращает `http://account.ely.by/code` в verification_uri.
  Мод принимает только ожидаемые host/path/порты, отклоняет userinfo, query,
  fragment и посторонние адреса и **всегда открывает HTTPS**, не HTTP.
- Access/refresh tokens хранятся в HardwareCrypt v2 как `deobso:ely_oauth_v1`.
  Старые OAuth-аккаунты и Yggdrasil-аккаунты `deobso:ely_v1` продолжают читаться.
  На другом компьютере аппаратное расшифрование может потребовать повторного входа.
- Ни коды, ни токены не включаются в toString, ошибки UI или логи сборки.

## Игровые сессии и скины

**Authlib-injector по-прежнему требуется для игрового входа и скинов Ely.by.**
Сам браузерный вход и сохранение аккаунта доступны без него.

Скачай совместимую версию: https://github.com/yushijinhun/authlib-injector/releases/latest

В JVM arguments отдельного профиля лаунчера добавь:

```text
-javaagent:/путь/к/authlib-injector.jar=ely.by
```

Перезапусти игру. Injector не кладётся в `mods`. Он меняет authlib глобально:
Microsoft-вход в таком запуске заблокирован, для него нужен профиль без agent.
Сервер должен поддерживать Ely.by; это не обход лицензии обычных Mojang-серверов.

## Проверка

`ElyDeviceTests` проверяет pending, slow_down, отказ, истечение срока, отмену,
редактирование/проверку адреса браузера и отсутствие секретов в диагностике.
`dev/check_ely_device.py` делает публичную проверку сервиса: выдача device/user
codes и `/validate` без входа в пользовательский аккаунт. Коды не печатаются,
доступ к аккаунту не предоставляется. Реальный вход с 2FA и UI нужно проверить
на компьютере игрока.

Исходники протокола сервиса:
- https://github.com/elyby/accounts/blob/master/api/tests/functional/oauth/DeviceCodeCest.php
- https://github.com/elyby/accounts-frontend/blob/master/packages/app/components/auth/actions.ts
- https://github.com/elyby/accounts-frontend/blob/master/packages/app/services/authFlow/InitOAuthAuthCodeFlowState.ts
