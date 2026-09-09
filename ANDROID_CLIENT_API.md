# Wandering Table — API для Android-клиента (Kotlin)

Документация по взаимодействию мобильного приложения с backend-сервером Wandering Table.
Актуальна по исходному коду сервера в `src/main/kotlin/ru/gohasoft/wanderingtable`.

## Базовые сведения

- **Base URL (dev):** `http://<host>:8050`
  - Порт задан в `application.properties` (`server.port=8050`), context-path отсутствует.
  - Для эмулятора Android — `http://10.0.2.2:8050`, для физического устройства — IP машины с сервером.
- **Формат данных:** JSON (`Content-Type: application/json`).
- **Аутентификация:** JWT, заголовок `Authorization: Bearer <accessToken>`.
- **CSRF:** отключён.
- **Публичные маршруты (без токена):** `GET /`, всё под `/auth/**`, `GET /notes`.
  Остальные требуют валидный access-токен.
- **Формат ошибок единый** для всех эндпоинтов — см. раздел 8.

### Роли

Пользователь имеет **набор** ролей (не одну).

| Роль | Что даёт |
|---|---|
| `PLAYER` | Базовая, выдаётся при регистрации. Создание заявок на партию, вступление в события. Снять её нельзя. |
| `NEWS_CREATOR` | Публикация новостей. |
| `GAME_CREATOR` | Добавление настолок в справочник. |
| `TOURNAMENT_CREATOR` | Создание турниров, чемпионатов и лиг. |
| `CLUB_MANAGER` | Заведующий клуба: единственная роль, выдающая роли. Может управлять любым событием и удалять любую новость. |

Роли выдаёт только `CLUB_MANAGER` через `PATCH /users/{id}/roles`. Попытка выдать роль себе
обычным игроком возвращает **403**. Снятие роли действует немедленно — сервер читает роли
из БД на каждый запрос, ждать истечения access-токена не нужно.

### Постраничные ответы

Все списочные эндпоинты принимают `?page=0&size=20&sort=field,DESC` и возвращают:

```json
{
  "content": [ /* элементы */ ],
  "page": 0,
  "size": 20,
  "totalElements": 42,
  "totalPages": 3
}
```

---

## 1. Аутентификация

### 1.1 Регистрация — `POST /auth/register`

```json
{ "name": "Иван", "email": "user@example.com", "password": "Password1" }
```

Валидация:
- `name` — не пустое, до 64 символов.
- `email` — валидный адрес. **Пробелы по краям не обрезаются, а считаются ошибкой** (400).
  Регистр приводится к нижнему, поэтому `Ivan@Example.COM` и `ivan@example.com` — один и тот же пользователь.
- `password` — минимум 8 символов, хотя бы одна строчная, одна заглавная и одна цифра.

Ответ **200 OK** (токены не выдаются, нужен отдельный `/auth/login`):

```json
{ "id": "9f1c…", "name": "Иван", "email": "user@example.com", "roles": ["PLAYER"] }
```

Ошибки: **409** — email занят; **400** — валидация; **429** — превышен лимит регистраций с одного IP
(по умолчанию 5 в минуту).

### 1.2 Логин — `POST /auth/login`

```json
{ "email": "user@example.com", "password": "Password1" }
```

Ответ **200 OK**:

```json
{ "accessToken": "eyJhbGciOiJIUzI1NiIs…", "refreshToken": "eyJhbGciOiJIUzI1NiIs…" }
```

Ошибка: **401** при неверном email или пароле (единое сообщение, без подсказки, что именно неверно).

### 1.3 Обновление токенов — `POST /auth/refresh`

```json
{ "refreshToken": "…" }
```

Ответ — та же структура, что у логина.

**Refresh-токен одноразовый.** При обмене старый удаляется на сервере; клиент обязан сохранить
новый. Повторное использование израсходованного токена — **401**. При одновременных попытках
обменять один токен успех получит ровно один запрос, остальные — 401.

Вход с нескольких устройств поддерживается: у каждого своя независимая сессия, вход на планшете
не завершает сессию на телефоне.

### 1.4 Выход — `POST /auth/logout`

```json
{ "refreshToken": "…" }
```

Ответ **204 No Content**. Идемпотентен: повторный вызов также 204.

### 1.5 Запрос кода сброса пароля — `POST /auth/forgot-password`

```json
{ "email": "user@example.com" }
```

Ответ **204 No Content** — **всегда**, в том числе для незарегистрированного адреса. По коду ответа
нельзя выяснить, есть ли такой аккаунт, поэтому экран должен показывать одно и то же сообщение
в обоих случаях.

Сервер отправляет на почту **шестизначный код**, действующий 15 минут. Новый запрос отменяет
предыдущий код: живым остаётся ровно один.

Ошибки: **400** — некорректный email; **429** — превышен лимит (по умолчанию 10 запросов
за 15 минут, отдельное окно на IP и на email).

### 1.6 Обмен кода на resetToken — `POST /auth/verify-reset-code`

```json
{ "email": "user@example.com", "code": "123456" }
```

Ответ **200 OK**:

```json
{ "resetToken": "sT9_kQ…" }
```

`resetToken` — непрозрачная строка, действует 10 минут и расходуется один раз.

Код расходуется при первой же проверке, независимо от результата: после неудачной попытки нужен
новый код через `/auth/forgot-password`.

Ошибки: **400** — код не из шести цифр; **401** — код неверный, просроченный или уже использован
(единое сообщение, без подсказки, что именно не так); **429** — превышен лимит.

### 1.7 Установка нового пароля — `POST /auth/reset-password`

```json
{ "resetToken": "sT9_kQ…", "newPassword": "Password1" }
```

Ответ **204 No Content**.

Требования к `newPassword` те же, что при регистрации: минимум 8 символов, хотя бы одна строчная,
одна заглавная и одна цифра.

**После успешной смены пароля все refresh-токены пользователя отзываются** — все устройства
разлогиниваются. Клиент должен вернуть пользователя на экран входа, а не пытаться обновить сессию.

Ошибки: **400** — пароль не проходит правила; **401** — `resetToken` неверный, просроченный
или уже использован.

### Время жизни токенов

| Токен | TTL |
|---|---|
| Access | 15 минут |
| Refresh | 30 дней |

Стратегия клиента: при 401 на защищённом эндпоинте один раз попробовать `/auth/refresh`
и повторить запрос; если refresh тоже вернул 401 — считать сессию истёкшей и разлогинить.

---

## 2. Профиль и статистика

### 2.1 Свой профиль — `GET /users/me`

Профиль и статистика одним запросом:

```json
{
  "user": { "id": "9f1c…", "name": "Иван", "email": "user@example.com", "roles": ["PLAYER", "NEWS_CREATOR"] },
  "stats": {
    "userId": "9f1c…",
    "gamesPlayed": 12,
    "wins": 7,
    "draws": 1,
    "losses": 4,
    "favoriteGames": [
      { "gameId": "a1…", "name": "Каркассон", "playedCount": 5 }
    ]
  }
}
```

`favoriteGames` — топ-5 игр по числу сыгранных партий, в порядке убывания. Считается запросом
на момент чтения, включает и клубные, и турнирные партии.

### 2.2 Смена имени — `PATCH /users/me`

```json
{ "name": "Новое имя" }
```

Ответ — объект пользователя (как `user` выше). Пустое имя — **400**.

### 2.3 Статистика любого игрока — `GET /users/{id}/stats`

Тот же объект, что `stats` в профиле.

### 2.4 История партий — `GET /users/{id}/games`

Постраничный список партий (`GameEvent`), в которых игрок участвовал: и `REGULAR_GAME`,
и `TOURNAMENT_GAME`. Сортировка по умолчанию — `startsAt,DESC`. Элементы — объекты события
(раздел 4.1).

### 2.5 Поиск участника по email — `GET /users?email=user@example.com`

Только `CLUB_MANAGER`. Превращает адрес в идентификатор — без него нечем вызвать 2.6,
который принимает id.

Ответ — объект пользователя (как `user` в 2.1), **вместе с ролями**:

```json
{ "id": "9f1c…", "name": "Иван", "email": "user@example.com", "roles": ["PLAYER", "NEWS_CREATOR"] }
```

Роли отдаются намеренно: 2.6 заменяет набор целиком, поэтому чтобы *добавить* роль, клиент
обязан сначала прочитать текущие и отправить объединение — иначе остальные роли будут стёрты.

Email нормализуется так же, как при регистрации и логине (`trim` + нижний регистр), так что
регистр и пробелы в запросе значения не имеют.

Ошибки: **403** — вызывающий не заведующий; **404** — такого адреса в клубе нет.

### 2.6 Изменение ролей — `PATCH /users/{id}/roles`

Только `CLUB_MANAGER`.

```json
{ "roles": ["NEWS_CREATOR", "TOURNAMENT_CREATOR"] }
```

**Это замена, а не добавление:** переданный набор становится новым целиком. Чтобы выдать роль,
не сняв остальные, прочитайте текущие через 2.5 (или 2.1 для себя) и пришлите объединение.

Ответ — объект пользователя. `PLAYER` добавляется автоматически, даже если не передан.

Заведующий может менять роли и себе — это штатный способ выдать себе `GAME_CREATOR` или
`NEWS_CREATOR`. Иерархии ролей нет: `CLUB_MANAGER` сам по себе не даёт прав на создание игр
или новостей.

Ошибки: **403** — вызывающий не заведующий; **400** — неизвестное имя роли;
**409** — попытка снять `CLUB_MANAGER` с себя или с последнего заведующего клуба.

---

## 3. Устройства для push-уведомлений (Firebase)

### 3.1 Привязка — `PUT /users/me/devices`

```json
{ "fcmToken": "fcm-registration-token", "platform": "ANDROID" }
```

`platform`: `ANDROID` | `IOS` | `WEB`.

Ответ **200 OK**:

```json
{ "id": "d1…", "platform": "ANDROID", "updatedAt": "2026-07-30T10:15:30Z" }
```

Сам токен в ответе не возвращается — это учётные данные устройства.

Операция идемпотентна: повторная привязка того же токена обновляет запись. Если токен ранее
принадлежал другому пользователю (переустановка приложения, смена владельца устройства),
привязка переходит к текущему — старый пользователь перестаёт получать уведомления на это устройство.

Клиенту следует вызывать этот эндпоинт после логина и при каждом обновлении FCM-токена.

### 3.2 Отвязка — `DELETE /users/me/devices`

Тело: `{ "fcmToken": "…" }`. Ответ **204**. Вызывать при логауте.

### 3.3 Свои устройства — `GET /users/me/devices`

Массив объектов из 3.1.

---

## 4. Справочник игр

`Game` — это настольная игра как таковая («Каркассон»), а не партия.

### 4.1 Создание — `POST /games`

Требует роль `GAME_CREATOR`.

```json
{
  "name": "Каркассон",
  "description": "Про черепицу и рыцарей",
  "minPlayers": 2,
  "maxPlayers": 5,
  "resultType": "POINTS"
}
```

`resultType` определяет, какие результаты принимаются у партий по этой игре:

| Значение | Что присылать при завершении партии |
|---|---|
| `WIN_LOSS` | `outcome`: `WIN` \| `LOSS` \| `DRAW` |
| `POINTS` | `score` (целое, может быть отрицательным) |
| `PLACEMENT` | `place` (целое ≥ 1) |

Ответ **200 OK**:

```json
{
  "id": "a1…", "name": "Каркассон", "description": "…",
  "minPlayers": 2, "maxPlayers": 5, "resultType": "POINTS",
  "creatorId": "9f1c…", "createdAt": "2026-07-30T10:15:30Z"
}
```

Ошибки: **403** без роли; **409** — игра с таким названием уже есть; **400** — `maxPlayers < minPlayers`
или неизвестный `resultType`.

### 4.2 Список и поиск — `GET /games?name=карк&page=0&size=20`

Постраничный список. `name` — необязательный фильтр по подстроке без учёта регистра.

### 4.3 Одна игра — `GET /games/{id}` → объект из 4.1, либо **404**.

---

## 5. События клуба

Все события — наследники одной сущности, поэтому у них общий формат ответа с полем `type`
и общие операции вступления, старта и отмены.

| `type` | Что это |
|---|---|
| `REGULAR_GAME` | Обычная клубная партия (заявка «собираю игру») |
| `TOURNAMENT_GAME` | Партия внутри турнира, чемпионата или лиги |
| `SINGLE_TOURNAMENT` | Турнир на один-два дня |
| `CHAMPIONSHIP` | Длительный турнир с выбыванием |
| `LEAGUE` | Длительное мероприятие на набор очков |

### 5.1 Объект события

```json
{
  "id": "e1…",
  "type": "REGULAR_GAME",
  "title": "Партия в субботу",
  "description": "Собираемся в клубе",
  "gameId": "a1…",
  "creatorId": "9f1c…",
  "status": "PLANNED",
  "minParticipants": 2,
  "maxParticipants": 4,
  "participantsCount": 2,
  "createdAt": "2026-07-30T10:15:30Z",
  "startsAt": "2026-08-01T18:00:00Z",
  "durationMinutes": 90,
  "participants": ["9f1c…", "7b2d…"]
}
```

- `status`: `PLANNED` → `IN_PROGRESS` → `FINISHED`, либо `CANCELLED`.
- `startsAt` / `durationMinutes` заполнены только у партий (`REGULAR_GAME`, `TOURNAMENT_GAME`).
- `participants` присутствует только при запросе одного события (`GET /events/{id}`),
  в списках — `null`.

### 5.2 Создание заявки на партию — `POST /events/regular-games`

Требует базовую роль `PLAYER` (есть у всех). Это и есть «запрос оппонента».

```json
{
  "gameId": "a1…",
  "title": "Партия в субботу",
  "description": "Собираемся в клубе",
  "startsAt": "2026-08-01T18:00:00Z",
  "durationMinutes": 90,
  "minParticipants": 2,
  "maxParticipants": 4
}
```

Создатель автоматически становится первым участником (`participantsCount: 1`).

Ошибки: **404** — игры с таким `gameId` нет; **400** — границы участников не укладываются
в лимиты игры из справочника (например, 12 человек в игру на 2–5).

### 5.3 Расписание клуба — `GET /events`

Постраничный список **всех** типов событий. Фильтры: `status`, `gameId`.

### 5.4 Одно событие — `GET /events/{id}`

Объект из 5.1 вместе со списком `participants`. **404**, если события нет.

### 5.5 Вступление — `POST /events/{id}/join`

Тело не требуется. Ответ — обновлённый объект события.

Ошибки: **409** — мест нет, событие уже началось, или вы уже участник; **404** — события нет.

Сервер гарантирует, что число участников никогда не превысит `maxParticipants`, даже при
одновременных запросах, и что двойное нажатие кнопки не создаст двух участий.

### 5.6 Выход — `DELETE /events/{id}/leave`

Ответ **204**. Освобождает место.

Ошибки: **409** — событие уже началось, либо вы его создатель (создателю нужно отменить событие);
**404** — вы не участник.

### 5.7 Старт — `POST /events/{id}/start`

Только создатель или `CLUB_MANAGER`. Переводит `PLANNED` → `IN_PROGRESS`.

Ошибки: **403** — не ваше событие; **409** — участников меньше `minParticipants`, либо
событие уже не в `PLANNED`.

### 5.8 Завершение партии — `POST /events/{id}/finish`

Только создатель или `CLUB_MANAGER`. Событие должно быть в `IN_PROGRESS`.

```json
{
  "results": [
    { "userId": "9f1c…", "score": 42 },
    { "userId": "7b2d…", "score": 17 }
  ]
}
```

Состав `results` обязан **точно совпадать** со составом участников: ни пропусков, ни лишних людей,
ни дубликатов. Набор полей определяется `resultType` игры (таблица в 4.1); лишние поля отклоняются.

**Исход не присылается клиентом для `POINTS` и `PLACEMENT` — его вычисляет сервер:**
по очкам победителем становится набравший максимум (при равенстве максимума — `DRAW` у всех),
по местам победа у первого места (при разделённом первом месте — `DRAW`).

Ответ **200 OK**:

```json
{
  "event": { /* объект события, status: FINISHED */ },
  "results": [
    { "userId": "9f1c…", "resultType": "POINTS", "outcome": "WIN",  "score": 42, "place": null },
    { "userId": "7b2d…", "resultType": "POINTS", "outcome": "LOSS", "score": 17, "place": null }
  ]
}
```

Ошибки: **403** — не ваше событие; **409** — событие не в `IN_PROGRESS` или уже завершено
(повторное завершение статистику не удваивает); **400** — состав или набор полей результатов неверен.

### 5.9 Результаты партии — `GET /events/{id}/results`

Массив объектов результата из 5.8.

### 5.10 Отмена — `DELETE /events/{id}`

Только создатель или `CLUB_MANAGER`. Возвращает объект события со `status: CANCELLED`;
запись остаётся в истории и продолжает отдаваться по `GET /events/{id}`.

Ошибка **409**, если событие уже завершено или отменено.

---

## 6. Турниры, чемпионаты и лиги

### 6.1 Создание — `POST /events/tournaments`

Требует роль `TOURNAMENT_CREATOR`.

```json
{
  "kind": "SINGLE",
  "gameId": "a1…",
  "title": "Турнир клуба",
  "description": "Описание",
  "startsAt": "2026-08-10T10:00:00Z",
  "endsAt": "2026-08-11T20:00:00Z",
  "entryFee": "500.00",
  "expectedSkillLevel": "INTERMEDIATE",
  "minParticipants": 2,
  "maxParticipants": 8,
  "bracketStrategy": "ROUND_ROBIN",
  "eliminationStrategy": "LOSER_ELIMINATION",
  "scoringStrategy": "LEVEL_WEIGHTED",
  "seasonStart": null,
  "seasonEnd": null
}
```

- `kind`: `SINGLE` | `CHAMPIONSHIP` | `LEAGUE`.
- `expectedSkillLevel`: `BEGINNER` | `INTERMEDIATE` | `ADVANCED` | `PRO`.
- Поля стратегий необязательны, значения по умолчанию показаны выше. Сейчас у каждой стратегии
  одна реализация; перечень значений расширяем без изменения контракта.
- `bracketStrategy` применяется к `SINGLE` и `CHAMPIONSHIP`, `eliminationStrategy` — только
  к `CHAMPIONSHIP`, `scoringStrategy` / `seasonStart` / `seasonEnd` — только к `LEAGUE`.

Ответ **200 OK**:

```json
{
  "event": { /* объект события, type: SINGLE_TOURNAMENT */ },
  "startsAt": "2026-08-10T10:00:00Z",
  "endsAt": "2026-08-11T20:00:00Z",
  "entryFee": 500.00,
  "expectedSkillLevel": "INTERMEDIATE",
  "bracketStrategy": "ROUND_ROBIN",
  "eliminationStrategy": null,
  "currentRound": null,
  "scoringStrategy": null,
  "seasonStart": null,
  "seasonEnd": null
}
```

Ошибки: **403** без роли; **404** — нет игры; **400** — `maxParticipants < minParticipants`
или `endsAt` раньше `startsAt`.

### 6.2 Список — `GET /events/tournaments?status=PLANNED&gameId=a1…`

Постраничный список объектов из 6.1. Сортировка по умолчанию — `startsAt,ASC`.

### 6.3 Одно турнирное событие — `GET /events/tournaments/{id}`

Объект из 6.1; внутри `event` заполнен `participants`.

### 6.4 Вступление в турнир

Отдельного эндпоинта нет — используйте общий `POST /events/{id}/join` (5.5), передав id турнира.
Так же работают `DELETE /events/{id}/leave`, `POST /events/{id}/start` и `DELETE /events/{id}`.

### 6.5 Генерация сетки — `POST /events/tournaments/{id}/bracket`

Только создатель или `CLUB_MANAGER`. Применимо к `SINGLE` и `CHAMPIONSHIP`.

Создаёт партии (`TOURNAMENT_GAME`) по стратегии турнира и сразу расставляет в них участников.
Круговая система (`ROUND_ROBIN`) даёт `n·(n−1)/2` партий; внутри одного раунда пары не пересекаются.

Ответ **200 OK** — массив объектов событий (созданные партии).

Дальше каждая партия живёт обычным циклом: `POST /events/{gameId}/start`,
затем `POST /events/{gameId}/finish` с результатами (5.8).

Ошибки: **403** — не ваше событие; **409** — сетка уже сгенерирована, участников меньше
`minParticipants`, или это лига (у лиги сетки нет); **400** — игра не рассчитана на двух игроков,
а круговая сетка ставит пары.

### 6.6 Партии турнира — `GET /events/tournaments/{id}/games`

Массив объектов событий, отсортированный по раунду.

### 6.7 Партия лиги — `POST /events/tournaments/{id}/games`

У лиги нет фиксированной сетки: партии играются свободными составами. Создать партию может
**любой участник лиги**.

```json
{
  "participantIds": ["9f1c…", "7b2d…"],
  "startsAt": "2026-08-12T19:00:00Z",
  "durationMinutes": 45
}
```

Ответ — объект созданной партии (`type: TOURNAMENT_GAME`). Дальше — `start` и `finish` как обычно;
при завершении очки лиги начисляются автоматически.

Ошибки: **403** — вы не участник лиги; **400** — среди `participantIds` есть не члены лиги,
дубликаты, или их число не подходит игре; **409** — событие не лига, либо лига уже завершена.

### 6.8 Таблица лиги — `GET /events/tournaments/{id}/standings`

```json
[
  { "userId": "9f1c…", "points": 14, "level": 2 },
  { "userId": "7b2d…", "points": 3,  "level": 1 }
]
```

Отсортировано по убыванию очков. `level` **выводится из очков**, а не хранится отдельно.

Правило начисления (`LEVEL_WEIGHTED`): победа даёт `5 − (уровень − 1)` очков, но не меньше 1;
ничья — 1; поражение — 0. Уровень равен `1 + очки / 10`, максимум 10. Отсюда и требование
«чем выше уровень, тем сложнее набирать».

Ошибка **400**, если событие не лига.

### 6.9 Выбывание в чемпионате — `GET /events/tournaments/{id}/elimination`

```json
[
  { "userId": "9f1c…", "eliminatedAtRound": null, "stillIn": true },
  { "userId": "7b2d…", "eliminatedAtRound": 1,    "stillIn": false }
]
```

Заполняется автоматически при завершении партий чемпионата: по правилу `LOSER_ELIMINATION`
выбывает проигравший, ничья не выбивает никого.

Ошибка **400**, если событие не чемпионат.

---

## 7. Новости клуба

### 7.1 Лента — `GET /notes?page=0&size=20`

**Публичный** эндпоинт, токен не нужен. Постраничный список всех новостей,
по умолчанию сортировка `createdAt,DESC`.

```json
{
  "content": [
    {
      "id": "n1…",
      "title": "Турнир в субботу",
      "content": "Ждём всех",
      "createdAt": "2026-07-30T10:15:30Z",
      "ownerId": "9f1c…"
    }
  ],
  "page": 0, "size": 20, "totalElements": 1, "totalPages": 1
}
```

### 7.2 Свои новости — `GET /notes/my` — требует токен, тот же формат.

### 7.3 Создание и обновление — `POST /notes`

Требует роль `NEWS_CREATOR`.

```json
{ "id": null, "title": "Заголовок", "content": "Текст новости" }
```

`id` необязателен: если передан и запись существует — обновление (при этом `createdAt`
сохраняется исходным), иначе создание. Ответ — объект новости из 7.1.

Ошибки: **403** — нет роли, либо `id` принадлежит новости другого автора; **400** — пустой `title`.

### 7.4 Удаление — `DELETE /notes/{id}`

Ответ **204**. Удалять может автор или `CLUB_MANAGER`.

Ошибки: **404** — новости нет; **403** — чужая новость (ранее сервер возвращал 200, ничего не удаляя).

---

## 8. Формат ошибок

Единый для всех эндпоинтов:

```json
{ "status": 400, "errors": ["Title can't be blank."] }
```

`errors` — массив: при ошибке валидации там может быть несколько сообщений, в остальных случаях одно.

| Код | Когда |
|---|---|
| 400 | Ошибка валидации, нечитаемый JSON, неизвестное значение enum, нарушение доменного правила |
| 401 | Нет токена, токен невалиден или истёк; неверные логин/пароль; израсходованный refresh-токен |
| 403 | Не хватает роли, либо операция над чужим объектом |
| 404 | Объект не найден |
| 409 | Конфликт: дубликат email или названия игры, нет мест, событие в неподходящем статусе, повторное завершение |
| 429 | Превышен лимит попыток регистрации или сброса пароля |
| 500 | Внутренняя ошибка. Тело не содержит ни сообщения, ни стектрейса |

Ориентируйтесь в первую очередь на HTTP-статус; текст сообщений может меняться.

---

## 9. Пример интеграции на Kotlin (Retrofit + OkHttp)

### 9.1 Retrofit-интерфейс

```kotlin
interface WanderingTableApi {

    // --- Аутентификация ---
    @POST("auth/register")
    suspend fun register(@Body body: RegisterRequest): UserDto

    @POST("auth/login")
    suspend fun login(@Body body: LoginRequest): TokenPairDto

    @POST("auth/refresh")
    suspend fun refresh(@Body body: RefreshRequest): TokenPairDto

    @POST("auth/logout")
    suspend fun logout(@Body body: RefreshRequest)

    // --- Профиль ---
    @GET("users/me")
    suspend fun me(): UserProfileDto

    @PATCH("users/me")
    suspend fun updateName(@Body body: UpdateNameRequest): UserDto

    @PATCH("users/{id}/roles")
    suspend fun updateRoles(@Path("id") id: String, @Body body: UpdateRolesRequest): UserDto

    @GET("users/{id}/stats")
    suspend fun stats(@Path("id") id: String): UserStatsDto

    @GET("users/{id}/games")
    suspend fun gameHistory(
        @Path("id") id: String,
        @Query("page") page: Int = 0,
        @Query("size") size: Int = 20
    ): PageDto<EventDto>

    // --- Устройства ---
    @PUT("users/me/devices")
    suspend fun registerDevice(@Body body: RegisterDeviceRequest): UserDeviceDto

    @HTTP(method = "DELETE", path = "users/me/devices", hasBody = true)
    suspend fun unregisterDevice(@Body body: UnregisterDeviceRequest)

    // --- Справочник игр ---
    @POST("games")
    suspend fun createGame(@Body body: CreateGameRequest): GameDto

    @GET("games")
    suspend fun games(
        @Query("name") name: String? = null,
        @Query("page") page: Int = 0,
        @Query("size") size: Int = 20
    ): PageDto<GameDto>

    // --- События ---
    @POST("events/regular-games")
    suspend fun createRegularGame(@Body body: CreateRegularGameRequest): EventDto

    @GET("events")
    suspend fun events(
        @Query("status") status: String? = null,
        @Query("gameId") gameId: String? = null,
        @Query("page") page: Int = 0,
        @Query("size") size: Int = 20
    ): PageDto<EventDto>

    @GET("events/{id}")
    suspend fun event(@Path("id") id: String): EventDto

    @POST("events/{id}/join")
    suspend fun join(@Path("id") id: String): EventDto

    @DELETE("events/{id}/leave")
    suspend fun leave(@Path("id") id: String)

    @POST("events/{id}/start")
    suspend fun start(@Path("id") id: String): EventDto

    @POST("events/{id}/finish")
    suspend fun finish(@Path("id") id: String, @Body body: FinishRequest): FinishedEventDto

    @DELETE("events/{id}")
    suspend fun cancel(@Path("id") id: String): EventDto

    // --- Турниры ---
    @POST("events/tournaments")
    suspend fun createTournament(@Body body: CreateTournamentRequest): TournamentDto

    @GET("events/tournaments")
    suspend fun tournaments(
        @Query("status") status: String? = null,
        @Query("gameId") gameId: String? = null,
        @Query("page") page: Int = 0,
        @Query("size") size: Int = 20
    ): PageDto<TournamentDto>

    @POST("events/tournaments/{id}/bracket")
    suspend fun generateBracket(@Path("id") id: String): List<EventDto>

    @POST("events/tournaments/{id}/games")
    suspend fun createLeagueGame(
        @Path("id") id: String,
        @Body body: CreateLeagueGameRequest
    ): EventDto

    @GET("events/tournaments/{id}/standings")
    suspend fun leagueStandings(@Path("id") id: String): List<LeagueStandingDto>

    @GET("events/tournaments/{id}/elimination")
    suspend fun elimination(@Path("id") id: String): List<ChampionshipStandingDto>

    // --- Новости ---
    @GET("notes")
    suspend fun news(
        @Query("page") page: Int = 0,
        @Query("size") size: Int = 20
    ): PageDto<NewsNoteDto>

    @POST("notes")
    suspend fun saveNote(@Body body: NewsNoteRequest): NewsNoteDto

    @DELETE("notes/{id}")
    suspend fun deleteNote(@Path("id") id: String)
}
```

### 9.2 Модели

```kotlin
data class RegisterRequest(val name: String, val email: String, val password: String)
data class LoginRequest(val email: String, val password: String)
data class RefreshRequest(val refreshToken: String)
data class TokenPairDto(val accessToken: String, val refreshToken: String)

data class UserDto(
    val id: String,
    val name: String,
    val email: String,
    val roles: Set<String>
)

data class UserProfileDto(val user: UserDto, val stats: UserStatsDto)

data class UserStatsDto(
    val userId: String,
    val gamesPlayed: Long,
    val wins: Long,
    val draws: Long,
    val losses: Long,
    val favoriteGames: List<FavoriteGameDto>
)

data class FavoriteGameDto(val gameId: String, val name: String, val playedCount: Long)

data class UpdateNameRequest(val name: String)
data class UpdateRolesRequest(val roles: Set<String>)

data class RegisterDeviceRequest(val fcmToken: String, val platform: String = "ANDROID")
data class UnregisterDeviceRequest(val fcmToken: String)
data class UserDeviceDto(val id: String, val platform: String, val updatedAt: String)

data class CreateGameRequest(
    val name: String,
    val description: String = "",
    val minPlayers: Int,
    val maxPlayers: Int,
    val resultType: String
)

data class GameDto(
    val id: String,
    val name: String,
    val description: String,
    val minPlayers: Int,
    val maxPlayers: Int,
    val resultType: String,
    val creatorId: String,
    val createdAt: String
)

data class PageDto<T>(
    val content: List<T>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int
)

data class EventDto(
    val id: String,
    val type: String,
    val title: String,
    val description: String,
    val gameId: String,
    val creatorId: String,
    val status: String,
    val minParticipants: Int,
    val maxParticipants: Int,
    val participantsCount: Int,
    val createdAt: String,
    val startsAt: String? = null,
    val durationMinutes: Int? = null,
    val participants: List<String>? = null
)

data class CreateRegularGameRequest(
    val gameId: String,
    val title: String,
    val description: String = "",
    val startsAt: String,
    val durationMinutes: Int? = null,
    val minParticipants: Int,
    val maxParticipants: Int
)

data class SubmitResultDto(
    val userId: String,
    val outcome: String? = null,
    val score: Int? = null,
    val place: Int? = null
)

data class FinishRequest(val results: List<SubmitResultDto>)

data class GameResultDto(
    val userId: String,
    val resultType: String,
    val outcome: String,
    val score: Int? = null,
    val place: Int? = null
)

data class FinishedEventDto(val event: EventDto, val results: List<GameResultDto>)

data class CreateTournamentRequest(
    val kind: String,
    val gameId: String,
    val title: String,
    val description: String = "",
    val startsAt: String,
    val endsAt: String? = null,
    val entryFee: String = "0.00",
    val expectedSkillLevel: String = "BEGINNER",
    val minParticipants: Int,
    val maxParticipants: Int
)

data class TournamentDto(
    val event: EventDto,
    val startsAt: String,
    val endsAt: String?,
    val entryFee: Double,
    val expectedSkillLevel: String,
    val bracketStrategy: String? = null,
    val eliminationStrategy: String? = null,
    val currentRound: Int? = null,
    val scoringStrategy: String? = null,
    val seasonStart: String? = null,
    val seasonEnd: String? = null
)

data class CreateLeagueGameRequest(
    val participantIds: List<String>,
    val startsAt: String,
    val durationMinutes: Int? = null
)

data class LeagueStandingDto(val userId: String, val points: Int, val level: Int)
data class ChampionshipStandingDto(
    val userId: String,
    val eliminatedAtRound: Int?,
    val stillIn: Boolean
)

data class NewsNoteRequest(val id: String? = null, val title: String, val content: String)
data class NewsNoteDto(
    val id: String,
    val title: String,
    val content: String,
    val createdAt: String,
    val ownerId: String
)

data class ErrorDto(val status: Int, val errors: List<String>)
```

Даты — ISO-8601 Instant в UTC, парсить через `java.time.Instant.parse`.

### 9.3 Хранение токенов

Хранить `accessToken` / `refreshToken` **только** в `EncryptedSharedPreferences`
(Jetpack Security) — они дают доступ ко всем данным пользователя.

```kotlin
class TokenStore(context: Context) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "auth_tokens",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    var accessToken: String?
        get() = prefs.getString("access_token", null)
        set(value) = prefs.edit { putString("access_token", value) }

    var refreshToken: String?
        get() = prefs.getString("refresh_token", null)
        set(value) = prefs.edit { putString("refresh_token", value) }

    fun clear() = prefs.edit { clear() }
}
```

### 9.4 OkHttp Authenticator для автообновления токена

Refresh-токен одноразовый, поэтому обновление обязано быть атомарным — иначе при нескольких
параллельных 401 старый токен уйдёт дважды и второй запрос получит 401.

```kotlin
class TokenAuthenticator(
    private val tokenStore: TokenStore,
    private val authApiProvider: () -> WanderingTableApi // клиент без AuthInterceptor
) : Authenticator {

    private val lock = Any()

    override fun authenticate(route: Route?, response: Response): Request? {
        if (responseCount(response) >= 2) return null

        synchronized(lock) {
            val currentAccessToken = tokenStore.accessToken
            // Токен мог обновиться в другом потоке — просто повторяем с новым.
            val headerToken = response.request.header("Authorization")?.removePrefix("Bearer ")
            if (currentAccessToken != null && headerToken != currentAccessToken) {
                return response.request.newBuilder()
                    .header("Authorization", "Bearer $currentAccessToken")
                    .build()
            }

            val refreshToken = tokenStore.refreshToken ?: return null

            return try {
                val newTokens = runBlocking {
                    authApiProvider().refresh(RefreshRequest(refreshToken))
                }
                tokenStore.accessToken = newTokens.accessToken
                tokenStore.refreshToken = newTokens.refreshToken

                response.request.newBuilder()
                    .header("Authorization", "Bearer ${newTokens.accessToken}")
                    .build()
            } catch (e: Exception) {
                tokenStore.clear() // сессия истекла
                null
            }
        }
    }

    private fun responseCount(response: Response): Int {
        var result = 1
        var prior = response.priorResponse
        while (prior != null) {
            result++
            prior = prior.priorResponse
        }
        return result
    }
}
```

### 9.5 Interceptor для проставления access-токена

```kotlin
class AuthInterceptor(private val tokenStore: TokenStore) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val token = tokenStore.accessToken ?: return chain.proceed(original)
        return chain.proceed(
            original.newBuilder().header("Authorization", "Bearer $token").build()
        )
    }
}

val okHttpClient = OkHttpClient.Builder()
    .addInterceptor(AuthInterceptor(tokenStore))
    .authenticator(TokenAuthenticator(tokenStore) { plainAuthApi })
    .build()
```

---

## 10. Проверка доступности сервера — `GET /`

Публичный, без авторизации. Ответ **200 OK**, тело — обычная строка (не JSON):

```
Everything cool!
```

Удобно для health-check перед показом экрана логина.

---

## 11. Особенности реализации, важные для клиента

- **Идентификаторы** — строковые UUID.
- **Роли** приходят массивом; проверять их надо через `contains`, а не сравнением с одним значением.
- **Снятие роли действует сразу**, без ожидания истечения access-токена: сервер читает роли из БД
  на каждый запрос. Если UI кеширует роли, обновляйте их при получении 403.
- **`POST /notes` — единый upsert** по `id`, а не отдельные «создать» и «обновить».
  `createdAt` при обновлении сохраняется.
- **Исход партии для `POINTS` и `PLACEMENT` считает сервер.** Не присылайте `outcome` для этих
  типов — запрос будет отклонён.
- **Состав результатов при завершении партии обязан совпадать со составом участников** —
  иначе 400. Собирайте результаты по списку `participants` из `GET /events/{id}`.
- **Повторное завершение партии возвращает 409** и не удваивает статистику; обрабатывайте
  этот код как «уже сделано».
- **Вступление ограничено `maxParticipants` строго**: при одновременных запросах лишние
  получат 409. Показывайте пользователю актуальный `participantsCount` из ответа.
- **Статистика и таблица лиги считаются на момент чтения**; кешировать их надолго не стоит.
- **Уровень в лиге не хранится**, а выводится из очков — не сохраняйте его отдельно от очков.
- **Лимит регистраций** локален для инстанса сервера: при нескольких репликах фактический лимит
  выше номинального.
