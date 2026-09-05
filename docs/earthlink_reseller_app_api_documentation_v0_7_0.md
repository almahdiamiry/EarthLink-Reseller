# Earthlink Reseller App API Documentation

**Version:** v0.7.0 baseline  
**Base URL:** `https://rapi.earthlink.iq/api/reseller/`  
**Client type:** Mobile-app API / reseller API  
**Current CLI implementation:** `earthlink_app_api_poc_v0_7_0.py`

---

## 1. Scope and confirmed status

This document describes the API endpoints used by the Earthlink reseller CLI after the move from the web portal API to the mobile-app API.

Confirmed working in live tests:

| Workflow | Status |
|---|---|
| Login/token | Confirmed |
| User listing/search/details | Confirmed |
| Create Test User | Confirmed |
| Create Using Deposit | Confirmed |
| Refill user deposit | Confirmed |
| Extend user | Confirmed |
| Transactions/account statement | Confirmed |
| Card prices | Confirmed |
| Prepaid-needed forecast | Confirmed |
| Password tools | Implemented / endpoint confirmed |
| User profile update actions | Implemented / endpoint confirmed |

Notes:

- The reseller balance endpoint may lag for a few seconds after financial write actions. The CLI retries balance verification after create/refill.
- `--enable-write` is no longer required. Write actions are available by default but still require confirmations and passwords where needed.
- The CLI keeps a local audit log for write actions in `earthlink_action_log.jsonl`.
- Sensitive values such as passwords, deposit passwords, bearer tokens, and authorization headers must be masked in logs.

---

## 2. Common HTTP behavior

### 2.1 Base URL

```text
https://rapi.earthlink.iq/api/reseller/
```

All paths in this document are relative to the base URL.

Example:

```text
GET /affiliate/deposit/balance
```

means:

```text
GET https://rapi.earthlink.iq/api/reseller/affiliate/deposit/balance
```

### 2.2 Default headers

After login, all API requests should include:

```http
Authorization: Bearer <access_token>
User-Agent: Android 9; Resellers 40001; PythonPOC
Accept: application/json, text/plain, */*
```

For form requests:

```http
Content-Type: application/x-www-form-urlencoded
```

For JSON requests:

```http
Content-Type: application/json; charset=UTF-8
```

Some captured mobile-app requests also include:

```http
affiliateindex: 0
```

The CLI works without explicitly relying on this header for the implemented workflows.

### 2.3 Response envelope

Most endpoints return an envelope similar to:

```json
{
  "value": {},
  "responseMessage": null,
  "error": null,
  "statusCode": null,
  "isSuccessful": true
}
```

Common handling:

| Field | Meaning |
|---|---|
| `isSuccessful` | API-level status. Can be `false` even with HTTP 200. |
| `value` | Main response data. May be object, list, boolean, numeric user index, etc. |
| `responseMessage` | Server success or info message. |
| `error` | Error object or string. Some validation failures are here. |
| `statusCode` | Usually null in observed responses. |

Important: Some business-rule failures return **HTTP 200** with `isSuccessful=false`. Do not treat HTTP 200 as success without checking the envelope.

### 2.4 Auth expiry behavior

If an API request returns `401`, the client should:

1. Clear the saved token.
2. Re-login.
3. Retry the failed request once.

The CLI supports automatic re-login using:

```env
EARTHLINK_USER=admin@sacx
EARTHLINK_PASS=<password>
```

or local remember-login storage:

```text
.earthlink_app_credentials.json
```

Local credential storage is convenient but plain JSON and should only be used on a private trusted machine.

---

## 3. Authentication

## `POST /token`

Login and obtain a bearer token.

### Content type

```http
application/x-www-form-urlencoded
```

### Request body

| Field | Type | Required | Notes |
|---|---:|---:|---|
| `username` | string | Yes | Reseller login username, for example `admin@sacx`. |
| `password` | string | Yes | Login password. |
| `loginType` | string | Yes | Observed value: `1`. |
| `grant_type` | string | Yes | Observed value: `password`. |

### Example

```http
POST /token

username=admin@sacx
password=****
loginType=1
grant_type=password
```

### Response

```json
{
  "access_token": "<jwt>",
  "token_type": "bearer",
  "expires_in": 3600
}
```

### Notes

- The JWT contains the reseller username, role, affiliate index, affiliate name, application name, issuer, audience, and expiry.
- Store `access_token` and send it in the `Authorization` header.

---

# 4. Dashboard / status endpoints

## `GET /affiliate/deposit/balance`

Returns current reseller deposit/balance.

### Request

No parameters.

### Response

`value` is usually a numeric balance.

### Usage

Used in:

- Main header.
- Refill preview.
- Create using Deposit preview.
- Balance verification after write actions.

### Notes

- Balance may lag for a few seconds after successful create/refill. Retry before declaring balance verification failed.

---

## `GET /testcount`

Returns the number of free test users available.

### Query parameters

| Parameter | Type | Required | Notes |
|---|---:|---:|---|
| `affiliateIndex` | string/int | No | Used during Create Test User. |

### Usage

- Main header.
- Create Test User flow.

---

## `GET /home/PrepaidNeeded`

Returns default prepaid-needed forecast used for dashboard/home forecast.

### Request

No parameters.

### Response

Contains prepaid-needed rows and total cost data. The CLI uses it to calculate:

```text
After forecast = balance - prepaid needed total
```

---

# 5. Account / package endpoints

## `GET /accounts/all`

Returns available subscription/account types.

### Response fields commonly used

| Field | Meaning |
|---|---|
| `accountIndex` | Package/account ID. |
| `accountName` | Package/account name. |
| `canTest` or similar | Whether test users are allowed for this package. |

### Usage

- Create Test User.
- Create Using Deposit.
- Change account type.
- Payment preview.

---

## `POST /affiliate/deposit/accountCost`

Returns the reseller cost for a selected account/package.

### Content type

```http
application/x-www-form-urlencoded
```

### Request body

| Field | Type | Required | Notes |
|---|---:|---:|---|
| `AccountID` | string/int | Yes | Account/package index. Example: `109`. |

### Response

Success usually includes package cost. When balance is insufficient, the endpoint may still return HTTP 200 but with `isSuccessful=false` and a useful message.

### Example insufficient balance message

```text
you dont have enough balance in your account !. Account cost is 90,000 IQD, your current balance is 85250
```

### Usage

- Create Using Deposit preview.
- Refill preview.

---

# 6. User list/search/detail endpoints

## `POST /user/all`

Returns users with pagination and optional filters.

### Content type

```http
application/x-www-form-urlencoded
```

### Request body

| Field | Type | Required | Notes |
|---|---:|---:|---|
| `StartIndex` | int | Yes | Zero-based row offset. |
| `RowCount` | int | Yes | Page size. |
| `OrderDescending` | bool string | Yes | `true` or `false`. |
| `OrderBy` | string | No | Optional sort field. |
| `AccountStatusID` | string/int | No | Filter status. |
| `TimePeriodID` | string/int | No | Used with status/time filters. |

### Confirmed filters

| CLI view | Request parameters |
|---|---|
| All users | `StartIndex`, `RowCount`, `OrderDescending=false` |
| Offline users | `AccountStatusID=4` |
| Expiring soon | `AccountStatusID=5`, `TimePeriodID=2` |
| Recently expired | `AccountStatusID=6`, `TimePeriodID=5` |
| Expired users | `AccountStatusID=6`, `TimePeriodID=7` |
| Online users | API filter was unreliable; CLI loads users and filters `onlineStatus` locally. |

### Response shape

```json
{
  "value": {
    "itemsList": [
      {
        "userIndex": 123,
        "userID": "user@sacx",
        "displayName": "name",
        "accountStatus": "Active",
        "onlineStatus": "Online",
        "accountIndex": "109",
        "accountName": "Economy",
        "mobileNumber": "0770...",
        "manualExpirationDate": "01/06/2026 12:17 PM"
      }
    ],
    "totalCount": 79
  },
  "isSuccessful": true
}
```

| Field | Meaning |
|---|---|
| `userIndex` | Primary numeric user identifier for most detail/actions. |
| `userID` | Username, for example `abbas@sacx`. |
| `displayName` | Display name. |
| `accountStatus` | `Active`, `ExpiringSoon`, `Suspended`, `SuspendedByAgent`, etc. |
| `activeDaysLeft` | Remaining active subscription days (e.g. `"00"`, `"01"`, `"25"`). |
| `manualExpirationDate` | Expiration timestamp string (e.g. `"06/09/2026 12:19 AM"`). |
| `onlineStatus` | `Online`, `Offline`, `OnlineNoNet`, etc. |
| `userIP` | IP where available. |
| `callerID` / `maxmac` | MAC lock / caller ID data. |
| `canExtendUser` | Whether extend may be possible. |
| `canRefill` | Whether refill may be possible. |
| `canChangeAccount` | Whether account-type change may be possible. |

### Observed Live Behavior & Classification Semantics

> **Note on Contract vs. Observation:** The official API schema returns `accountStatus`, `activeDaysLeft`, and `onlineStatus` as separate descriptive fields. The observations below describe proven ISP live behavior and edge cases established via API audits.

* **`activeDaysLeft` Semantics:**
  - **Observed formats:** String numbers with zero-padding (e.g. `"00"`, `"01"`, `"08"`, `"25"`), raw numbers, or empty/null for admin/unlimited accounts.
  - **Boundary Meaning (`<= 0`):** A value of `0`, `"00"`, or any value `<= 0.0` indicates that the subscription duration has fully elapsed.
* **`accountStatus` vs. Real Active Eligibility:**
  - `accountStatus` alone is **not** a sufficient single-field oracle for active subscription eligibility.
  - **Observed string lag:** When an active period ends, `accountStatus` may temporarily remain `"ExpiringSoon"` before batch transition to `"Suspended"` / `"Expired"`.
  - **Observed regression example:** In live ISP audits, user `hussam@sacx` (`userIndex = 10942873`) presented `accountStatus = "ExpiringSoon"`, `activeDaysLeft = "00"`, and `onlineStatus = "Online"`. Server-side ISP filtering (`AccountStatusID = 1`, Active) strictly excluded this subscriber from active counts (returning 43 active subscribers instead of 44), confirming that `activeDaysLeft <= 0` overrides `"ExpiringSoon"` into expired status.
* **`onlineStatus` Classification (`Online` vs. `OnlineNoNet`):**
  - **`Online`:** Active gateway session with full internet routing (`hasNoInternet = false`, IP allocated on CGNAT `100.x.x.x` or public pool).
  - **`OnlineNoNet`:** Active physical PPPoE connection to the NAS without internet routing (`hasNoInternet = true`, IP allocated on walled-garden/captive portal pool `10.2.72.x`). `OnlineNoNet` must **not** be classified as internet-connected `Online`.
  - **`Offline`:** No active NAS session.

---

## `GET /user/{userIndex}`

Returns full user detail by `userIndex`.

### Path parameters

| Parameter | Type | Required | Notes |
|---|---:|---:|---|
| `userIndex` | int/string | Yes | Numeric index returned by list/search. |

### Usage

- User details screen.
- Verification after create/refill/extend/update.
- Before update actions to fetch the latest object.

### Notes

Some `GET /user/{userIndex}` payloads contain the real index in nested `userObject.userIndex` even if top-level `userIndex` is `0`; always preserve/force the path index during updates.

---

## `GET /user/autocomplete`

Searches users by username, phone, display name, or customer-related text.

### Query parameters

| Parameter | Type | Required |
|---|---:|---:|
| `key` | string | Yes |

### Usage

- Search users.
- Resolve username to userIndex.
- User detail by username/phone/name.

### Notes

The response shape can vary. Normalize from any of:

```text
value
itemsList
items
users
```

Prefer exact `userID` match when resolving a username.

---

## `POST /user/checkuseravailable`

Checks username availability before user creation.

### Content type

```http
application/x-www-form-urlencoded
```

### Request body

| Field | Type | Required | Notes |
|---|---:|---:|---|
| `UserID` | string | Yes | Example: `newuser@sacx`. |

### Response

Observed behavior:

```text
value=true  -> username available
value=false -> username already exists / unavailable
```

### Usage

- Create Test User.
- Create Using Deposit.

---

# 7. User session / invoices / errors / test-user report

## `POST /usersession/active`

Returns active/online sessions.

### Content type

```http
application/x-www-form-urlencoded
```

### Request body

| Field | Type | Required |
|---|---:|---:|
| `StartIndex` | int | Yes |
| `RowCount` | int | Yes |

### Usage

- Sessions - online.
- User detail runtime hydration: IP/session/online status.

### Important fields

| Field | Meaning |
|---|---|
| `userObject.userIndex` | Canonical subscriber numeric index. (Note: Root `userIndex` in session rows is frequently `0`). |
| `userObject.userId` / `userID` | Subscriber username. |
| `userIp` / `userIP` | Allocated gateway/session IP. |
| `isOnline` | Physical session alive flag on NAS. |
| `hasNoInternet` | Boolean indicating captive portal / walled-garden state (`true` = no internet routing). |
| `usageTime` / `onlineTime` | Session duration string (e.g. `03:06:32` or `28d 19:28:40`). |
| `userMac` / `callerMAC` | Hardware MAC address. |

### Observed Live Behavior & Correlation Notes

> **Note on Contract vs. Observation:** `POST /usersession/active` serves raw NAS/RADIUS network connection telemetry rather than a pre-filtered dashboard subscriber list.

* **Network Session Scope:**
  - The endpoint returns all live PPPoE connections on the gateway hardware.
  - This includes subscribers whose account status is `Suspended` but who maintain an active physical connection in captive portal mode (`hasNoInternet = true`, IP in `10.2.72.x` pool, corresponding to `onlineStatus = "OnlineNoNet"` in `/user/all`).
* **Canonical Correlation Key:**
  - Correlation between `/user/all` and `/usersession/active` must use `userIndex` (`userObject.userIndex` in sessions matched against `userIndex` in user lists).
  - In live audits, the set of active sessions strictly equals:
    $$\text{Total Active Sessions} = (\text{Subscribers with } \texttt{onlineStatus = "Online"}) + (\text{Subscribers with } \texttt{onlineStatus = "OnlineNoNet"})$$

---

## `POST /userpayment/usersInvoice`

Returns invoices/payment records.

### Content type

```http
application/x-www-form-urlencoded
```

### Request body

| Field | Type | Required | Notes |
|---|---:|---:|---|
| `StartIndex` | int | Yes | Pagination. |
| `RowCount` | int | Yes | Page size. |
| `OrderByDesc` | bool string | Yes | Usually `true`. |
| `Query` | string | No | Used for user-specific invoice lookup. |

### Usage

- Invoices screen.
- User invoices/payments.

---

## `POST /userlog/all`

Returns user error/log rows.

### Content type

```http
application/x-www-form-urlencoded
```

### Request body

| Field | Type | Required |
|---|---:|---:|
| `StartIndex` | int | Yes |
| `RowCount` | int | Yes |

### Usage

- Errors screen.

---

## `GET /reports/testsUsed`

Returns used test-user report.

### Query parameters

| Parameter | Type | Required |
|---|---:|---:|
| `StartIndex` | int | Yes |
| `RowCount` | int | Yes |

### Usage

- Test users report.

---

# 8. Customer endpoints

## `POST /usercustomer/phone`

Looks up customers by phone number.

### Content type

```http
application/x-www-form-urlencoded
```

### Request body

| Field | Type | Required |
|---|---:|---:|
| `phoneNumber` | string | Yes |

### Usage

- Create Using Deposit.
- If a customer exists, select/reuse the customer ID.
- If not found, create customer before creating paid user.

---

## `POST /usercustomer/create`

Creates a customer record.

### Content type

```http
application/x-www-form-urlencoded
```

### Request body

| Field | Type | Required | Notes |
|---|---:|---:|---|
| `customerFullName` | string | Yes | Must pass backend Arabic-name validation. |
| `customerPhoneNumber` | string | Yes | Iraqi phone number. |
| `customerSecondPhoneNumber` | string | No | Only send if available. |
| `email` | string | No | CLI sends blank if unused. |
| `address` | string | No | CLI sends blank if unused. |

### Validation rule observed

The backend rejects non-Arabic/invalid names with:

```text
يجب ان يحتوي كل اسم على ما لا يقل عن حرفين الى 20 حرف عربي
```

Practical client validation:

- At least two name parts.
- Each part should be 2–20 Arabic letters.
- Example: `احمد محمد`.

### Response

On success, `value` is the new `customerId`.

---

# 9. Create user endpoints

## `POST /user/newtestuser`

Creates a free test user.

### Content type

```http
application/x-www-form-urlencoded
```

### Request body

| Field | Type | Required | Notes |
|---|---:|---:|---|
| `MobileNumber` | string | Yes | Customer phone. |
| `AccountIndex` | string/int | Yes | Package index. |
| `UserID` | string | Yes | Username. |
| `DisplayName` | string | Yes | Display name. |
| `AffiliateIndex` | string/int | Yes | Affiliate index from `/affiliates`. |
| `UserPass` | string | Yes | User password. |

### Successful response example

```json
{
  "value": 36328246,
  "responseMessage": "User almahdi@sacx has been created successfully",
  "isSuccessful": true
}
```

### Verification

After success:

1. Read `value` as new `userIndex`.
2. `GET /user/{userIndex}`.
3. Confirm username/package/phone/display name.
4. Free test count should decrease.

---

## `POST /user/newuserdeposit`

Creates a paid user using reseller deposit.

### Content type

```http
application/x-www-form-urlencoded
```

### Required request body

| Field | Type | Required | Notes |
|---|---:|---:|---|
| `UserID` | string | Yes | Username. |
| `UserPass` | string | Yes | User password. |
| `DisplayName` | string | Yes | Display name. |
| `MobileNumber` | string | Yes | Phone number. |
| `AffiliateIndex` | string/int | Yes | Affiliate index. |
| `DepositPassword` | string | Yes | Reseller deposit/online password. |
| `AccountIndex` | string/int | Yes | Package index. |
| `customerId` | string/int | Yes | Existing or newly created customer ID. |

### Optional request fields

The API method supports these optional fields, but the CLI does not prompt for them because they are not needed for the confirmed workflow:

```text
Status
PaymentDueDate
Email
Address
IdCardNumber
IdCardType
Gender
TypeOfUse
LocationDetails
```

### Pre-check flow

Before calling this endpoint:

1. Check username availability using `/user/checkuseravailable`.
2. Lookup/create customer using `/usercustomer/phone` and `/usercustomer/create`.
3. Select package from `/accounts/all`.
4. Get package cost using `/affiliate/deposit/accountCost`.
5. Ensure balance is enough.
6. Ask for deposit password.
7. Show final financial warning and confirm.

### Successful response example

```json
{
  "value": 36332059,
  "responseMessage": "User sajadzaki@sacx has been created successfully",
  "isSuccessful": true
}
```

### Failure examples

Wrong deposit password:

```json
{
  "isSuccessful": false,
  "error": {
    "message": "Invalid deposit password"
  }
}
```

Insufficient balance / business-rule failure:

```json
{
  "isSuccessful": false,
  "value": 0,
  "error": "User_ProcedureError"
}
```

### Verification after success

1. Balance should decrease by package cost.
2. `GET /user/{newUserIndex}` should return the new user.
3. Transaction statement should show:
   - `operation=Withdraw`
   - `description=Add user`
   - `userID=<new user>`
   - `withdrawal=<package cost>`

### Important balance note

The balance endpoint may lag immediately after successful creation. Retry balance verification for a few seconds before reporting final balance change.

---

# 10. Refill / extend / disconnect endpoints

## `POST /user/newrefilldeposit`

Refills an existing user using deposit/online payment.

### Content type

```http
application/json; charset=UTF-8
```

### Request body

```json
{
  "DepositPassword": "****",
  "UserID": "user@sacx"
}
```

### Pre-check flow

1. Load user detail.
2. Get account/package index from user.
3. Get package cost using `/affiliate/deposit/accountCost`.
4. Verify balance is enough.
5. Ask for deposit password.
6. Confirm.

### Verification

After success:

1. Refresh user with `GET /user/{userIndex}`.
2. Check status and expiry.
3. Check balance after sync/retry.
4. Confirm transaction statement shows `Description: Refill user`.

### Failure examples

Wrong deposit password returns a business failure message such as:

```text
Wrong account password
Invalid deposit password
```

Actual message can vary by endpoint/server response.

---

## `POST /user/extend/{userIndex}`

Extends user expiration by 24 hours when eligible.

### Content type

```http
application/json; charset=UTF-8
```

### Request body

```json
{}
```

### Path parameters

| Parameter | Type | Required |
|---|---:|---:|
| `userIndex` | int/string | Yes |

### Successful response example

```json
{
  "value": true,
  "responseMessage": "the user expiration has been extended for 24 hours",
  "isSuccessful": true
}
```

### Verification

After success:

1. Refresh user details.
2. Status may change from `Suspended` to `ExpiringSoon`.
3. Expiry should increase by about 24 hours.

---

## `POST /activesessions/disconnect`

Disconnects an online/active session.

### Content type

```http
application/json; charset=UTF-8
```

### Request body

```json
{
  "userindex": 26007023,
  "userid": "amir@sacx"
}
```

### Usage

- User detail action: Disconnect user.

### Verification

Refresh active sessions and/or user details after action.

---

# 11. Password endpoints

## `POST /user/showpassword`

Shows router/user password.

### Content type

```http
application/json; charset=UTF-8
```

### Request body

```json
{
  "userindex": 36332059,
  "userid": "user@sacx"
}
```

### Usage

- Password tools → Show router/user password.

---

## `POST /user/showaccountpassword`

Shows account password.

### Content type

```http
application/json; charset=UTF-8
```

### Request body

```json
{
  "userindex": 36332059,
  "userid": "user@sacx"
}
```

### Usage

- Password tools → Show account password.

---

## `POST /user/changepassword`

Changes router/user password.

### Content type

```http
application/json; charset=UTF-8
```

### Request body

```json
{
  "userindex": 36332059,
  "userid": "user@sacx",
  "NewPassword": "new-password"
}
```

---

## `POST /user/changeaccountpassword`

Changes account password.

### Content type

```http
application/json; charset=UTF-8
```

### Request body

```json
{
  "userindex": 36332059,
  "userid": "user@sacx",
  "NewPassword": "new-password"
}
```

---

# 12. User profile/update endpoints

## `POST /user/{userIndex}`

Updates user data using a mobile-app-shaped full user object.

### Content type

```http
application/json; charset=UTF-8
```

### Path parameters

| Parameter | Type | Required |
|---|---:|---:|
| `userIndex` | int/string | Yes |

### Used for

| CLI action | Important payload changes |
|---|---|
| Change display name | Update `displayName`; also update `arName` when present. |
| Change / clear MAC lock | Update `callerID` / `maxmac` / `maxMac`. Empty string clears. |
| Activate / deactivate | Use app toggle fields, especially `userActiveManage`. |

### Important update behavior

For update actions, do not construct a minimal payload unless confirmed by HAR. The working approach is:

1. Fetch fresh user object with `GET /user/{userIndex}`.
2. Merge runtime/session data if needed.
3. Force path `userIndex` into top-level and nested objects.
4. Remove CLI-only helper fields.
5. Modify the specific fields.
6. POST the full mobile-app-shaped object back to `/user/{userIndex}`.

### Activate/deactivate behavior

Confirmed behavior:

| Desired state | Key field |
|---|---|
| Activate | `userActiveManage=true` |
| Deactivate | `userActiveManage=false` |

Important notes:

- Keep `userActive=true`.
- Keep `isBlocked=false`.
- Do not manually force `accountStatus`; backend changes it.
- Deactivate may result in `accountStatus=SuspendedByAgent`.

---

## `POST /user/chnageaccounttype`

Changes user account/package type.

> Note: The backend path is misspelled as `chnageaccounttype`.

### Content type

```http
application/json; charset=UTF-8
```

### Request body

The CLI sends multiple key aliases to match the mobile HashMap behavior:

```json
{
  "userindex": 36332059,
  "userIndex": 36332059,
  "userid": "user@sacx",
  "UserID": "user@sacx",
  "accountIndex": 109,
  "AccountIndex": 109,
  "accountId": 109,
  "AccountID": 109
}
```

### Response notes

The server may reject changes outside the allowed change window. Always refresh user details to verify actual account type.

---

# 13. Business / card / transaction endpoints

## `GET /prepaycard/prices/forreseller`

Returns card/package prices for reseller view.

### Response fields commonly used

| Field | Meaning |
|---|---|
| `accountName` / name | Package name. |
| `accountIndex` / index | Package ID. |
| reseller price | Reseller/dealer cost. |
| user price | Retail/customer price. |
| custom flag | Whether price is custom. |

### Usage

- Card prices screen.

---

## `GET /prepaycard/prices/foruser`

Returns card/package prices for user/customer view.

### Usage

The reseller endpoint already includes both reseller and user prices in the current CLI output, but this endpoint remains documented.

---

## `POST /prepaycard/prepaidneeded`

Returns prepaid-needed forecast for a selected number of days.

### Content type

```http
application/x-www-form-urlencoded
```

### Request body

| Field | Type | Required |
|---|---:|---:|
| `Days` | int/string | Yes |

### Usage

- Prepaid-needed forecast screen.

### Notes

The app/server may return most useful data for 7-day forecast. Custom day values may not always behave like the web portal.

---

## `GET /affiliate/deposit/accountStatement`

Returns transaction/account statement.

### Query parameters

| Parameter | Type | Required | Notes |
|---|---:|---:|---|
| `StartIndex` | int | Yes | Pagination. |
| `RowCount` | int | Yes | Page size. |
| `Query` | string | No | Search/filter. |
| `OperationType` | string | No | Deposit/withdraw filter where supported. |
| `fromDate` | string | No | Start date. |
| `toDate` | string | No | End date. |
| `DepositPassword` | string | No/Yes | Mobile app asks before statement. |
| `TargetAffiliateIndex` | string/int | No | Used for affiliate-related filters. |
| `BatchNo` | string | No | Batch/reference filter. |

### Important response fields

| Field | Meaning |
|---|---|
| `operation` | `Withdraw` or `Deposit`. |
| `withdrawal` / `formattedWithdrawal` | Withdrawal amount. |
| `deposit` / `formattedDeposit` | Deposit amount. |
| `balance` / `formattedBalance` | Balance after transaction. |
| `userID` | Related user, if applicable. |
| `displayName` | Related user display name, if applicable. |
| `resellerUser` | Reseller/operator involved. |
| `transactionID` | Transaction reference. |
| `serials` | Card/serial reference where available. |
| `description` | Example: `Add user`, `Refill user`, `Transfer from ...`. |

### Notes

Do **not** use `balance` as the transaction amount. Use `withdrawal` or `deposit`. `balance` is the balance after transaction.

---

## `GET /prepaycard/filter/orderby`

Returns order-by filters for prepaid card/order history.

---

## `GET /prepaycard/filter/status`

Returns status filters for prepaid card/order history.

---

## `GET /prepaycard/order/list`

Returns prepay-card order history.

### Query parameters

| Parameter | Type | Required |
|---|---:|---:|
| `StartDate` | string | No |
| `EndDate` | string | No |
| `BatchNo` | string | No |
| `DepositPassword` | string | No/Yes depending on app flow |

### Notes

Not part of the main current daily workflow, but endpoint is documented.

---

## `GET /affiliates`

Returns reseller affiliate records.

### Usage

- Create Test User.
- Create Using Deposit.
- Transfer/balance-related helper.
- Identifies selected affiliate name/index, for example `sacx (46101)`.

---

## `GET /subaffiliates`

Returns sub-affiliates.

### Usage

- Transfer/card-affiliate related helper.
- Not part of daily confirmed workflow.

---

## `POST /prepaycard/changecardffiliate`

Changes card affiliate.

> Note: Backend path appears misspelled as `changecardffiliate`.

### Content type

```http
application/x-www-form-urlencoded
```

### Request body

| Field | Type | Required |
|---|---:|---:|
| `StartSerial` | string | Yes |
| `AffiliateTypeID` | string/int | Yes |
| `TargetAffiliate` | string/int | Yes |
| `ItemsCount` | string/int | Yes |

### Notes

Protected/less-used workflow. Keep confirmations and audit logging if used.

---

# 14. Endpoint status matrix

| Endpoint | Method | Status | Main usage |
|---|---:|---|---|
| `/token` | POST | Confirmed | Login |
| `/affiliate/deposit/balance` | GET | Confirmed | Balance |
| `/testcount` | GET | Confirmed | Free test count |
| `/home/PrepaidNeeded` | GET | Confirmed | Dashboard forecast |
| `/accounts/all` | GET | Confirmed | Package list |
| `/affiliate/deposit/accountCost` | POST | Confirmed | Payment preview |
| `/user/all` | POST | Confirmed | User lists |
| `/usersession/active` | POST | Confirmed | Online sessions/runtime details |
| `/userpayment/usersInvoice` | POST | Implemented | Invoices/payments |
| `/userlog/all` | POST | Implemented | Errors |
| `/reports/testsUsed` | GET | Implemented | Test users report |
| `/user/{userIndex}` | GET | Confirmed | User details |
| `/user/autocomplete` | GET | Confirmed | Search/resolve users |
| `/user/checkuseravailable` | POST | Confirmed | Username availability |
| `/usercustomer/phone` | POST | Confirmed | Customer lookup |
| `/usercustomer/create` | POST | Confirmed | Customer creation |
| `/user/newtestuser` | POST | Confirmed | Create test user |
| `/user/newuserdeposit` | POST | Confirmed | Create paid user |
| `/user/newrefilldeposit` | POST | Confirmed | Refill using deposit |
| `/user/extend/{userIndex}` | POST | Confirmed | Extend user |
| `/activesessions/disconnect` | POST | Implemented | Disconnect user |
| `/user/showpassword` | POST | Implemented | Show router/user password |
| `/user/showaccountpassword` | POST | Implemented | Show account password |
| `/user/changepassword` | POST | Implemented | Change router/user password |
| `/user/changeaccountpassword` | POST | Implemented | Change account password |
| `/user/{userIndex}` | POST | Implemented | Update profile/toggle/MAC/display |
| `/user/chnageaccounttype` | POST | Implemented | Change account type |
| `/prepaycard/prices/forreseller` | GET | Confirmed | Card prices |
| `/prepaycard/prices/foruser` | GET | Confirmed | User prices |
| `/prepaycard/prepaidneeded` | POST | Confirmed | Forecast |
| `/affiliate/deposit/accountStatement` | GET | Confirmed | Transactions |
| `/prepaycard/filter/orderby` | GET | Documented | Order filters |
| `/prepaycard/filter/status` | GET | Documented | Status filters |
| `/prepaycard/order/list` | GET | Documented | Orders |
| `/affiliates` | GET | Confirmed | Affiliate selection |
| `/subaffiliates` | GET | Documented | Sub-affiliate lookup |
| `/prepaycard/changecardffiliate` | POST | Documented | Change card affiliate |

---

# 15. Recommended implementation rules

## 15.1 Do not trust HTTP status alone

A valid business failure can be:

```text
HTTP 200
isSuccessful=false
```

Always parse `isSuccessful`, `value`, `responseMessage`, and `error`.

## 15.2 Always verify write actions

For write/financial actions:

1. Capture before snapshot.
2. Execute action.
3. Refresh user/balance/statement.
4. Show before/after.
5. Write local audit log.

## 15.3 Mask sensitive logs

Never print/store:

```text
Authorization
Bearer token
password
UserPass
DepositPassword
NewPassword
```

Use `****` in debug and audit logs.

## 15.4 Balance verification retry

After create/refill, retry balance for a few seconds because the app and API can show temporary loading/stale balance.

## 15.5 Local audit log

The CLI writes write-action records to:

```text
earthlink_action_log.jsonl
```

Each record contains:

```json
{
  "timestamp": "2026-05-02 12:17:58",
  "action": "create_using_deposit",
  "user_id": "sajadzaki@sacx",
  "user_index": "36332059",
  "operator": "admin@sacx",
  "success": true,
  "result": {},
  "before": {},
  "after": {},
  "extra": {}
}
```

Secrets are masked before writing.

---

# 16. Minimal Python request examples

## 16.1 Login

```python
import requests

BASE = "https://rapi.earthlink.iq/api/reseller/"

session = requests.Session()
resp = session.post(
    BASE + "token",
    data={
        "username": "admin@sacx",
        "password": "****",
        "loginType": "1",
        "grant_type": "password",
    },
    headers={
        "Content-Type": "application/x-www-form-urlencoded",
        "Accept": "application/json, text/plain, */*",
        "User-Agent": "Android 9; Resellers 40001; PythonPOC",
    },
)
token = resp.json()["access_token"]
session.headers.update({
    "Authorization": f"Bearer {token}",
    "User-Agent": "Android 9; Resellers 40001; PythonPOC",
})
```

## 16.2 List users

```python
resp = session.post(
    BASE + "user/all",
    data={
        "StartIndex": "0",
        "RowCount": "30",
        "OrderDescending": "false",
    },
)
payload = resp.json()
users = payload["value"]["itemsList"]
total = payload["value"]["totalCount"]
```

## 16.3 Create paid user using deposit

```python
resp = session.post(
    BASE + "user/newuserdeposit",
    data={
        "UserID": "newuser@sacx",
        "UserPass": "****",
        "DisplayName": "احمد محمد",
        "MobileNumber": "07700000000",
        "AffiliateIndex": "46101",
        "DepositPassword": "****",
        "AccountIndex": "109",
        "customerId": "3038123",
    },
)
payload = resp.json()

if not payload.get("isSuccessful"):
    raise RuntimeError(payload.get("error") or payload)
```

## 16.4 Extend user

```python
resp = session.post(
    BASE + "user/extend/26007023",
    json={},
)
payload = resp.json()
```

---

# 17. Current v1-readiness status

The API layer is now strong enough to support a GUI/desktop app after CLI hardening.

Recommended next work:

1. Keep v0.7.x for verification/audit/logging polish.
2. Use v0.8.x for configuration, export, and reliability.
3. Use v0.9.x as release candidate.
4. Move to v1.0 when:
   - No broken menu items.
   - Write actions all verify before/after.
   - Audit log is stable.
   - Credentials/token behavior is documented.
   - CLI can be used daily without debug mode.

