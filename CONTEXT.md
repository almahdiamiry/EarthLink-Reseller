# EarthLink Reseller V1 Domain Context

Domain glossary defining subscriber operational states and lifecycle classifications for the reseller application.

## Language

**Active Users**:
Subscribers with a valid, non-expired subscription whose account status is active and not suspended.
_Avoid_: Valid accounts, unexpired users

**Online Users**:
Subscribers who currently have an active network connection session (PPPoE session alive on gateway).
_Avoid_: Connected accounts, logged in users

**Offline Users**:
Subscribers with a valid active subscription whose network connection session is currently disconnected.
_Avoid_: Disconnected accounts, inactive network

**Users Expiring Soon**:
Subscribers whose active subscription will expire within two days (48 hours) or less.
_Avoid_: Near expiry, close to finish

**Recently Expired Users**:
Subscribers whose subscription ended within the last 7 days per EarthLink API specification.
_Avoid_: Freshly expired, latest lapsed

**Expired Users**:
All subscribers whose subscription period has elapsed and has not been renewed.
_Avoid_: Suspended accounts, terminated users
