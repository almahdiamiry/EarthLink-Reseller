# Feature Specification: Dashboard Status Cards & Filtered Subscriber Navigation

## Problem Statement

As an EarthLink reseller using the application, the "Status and Panels" screen is currently largely non-interactive. The six status cards display static or inaccurate figures and cannot be clicked. The only functional action on the screen is a floating action button (+) that opens the "create subscriber" flow, which is redundant because the global bottom navigation bar already provides a permanent "Create" tab right below it. When managing daily operations, the reseller cannot see who belongs to critical operational states—such as active subscribers whose routers are disconnected (Offline Users), subscribers whose service will cut off in the next 48 hours (Users Expiring Soon), or subscribers who expired within the last 7 days and require follow-up (Recently Expired Users). Furthermore, navigating into subscriber lists from the dashboard must maintain a clean back-stack so that returning brings the reseller back to the six cards rather than abruptly exiting the app.

## Solution

Revamp the Status and Panels screen to transform it into a responsive operational hub:
1. Replace the non-actionable "All" card with a dedicated "Recently Expired Users" card, resulting in six distinct operational categories: Active Users, Online Users, Offline Users, Users Expiring Soon, Recently Expired Users, and Expired Users.
2. Dynamically compute exact, live subscriber counts in memory from loaded subscriber records and local database accounts with zero hardcoded dummy values.
3. Make all six status cards interactive. Tapping any card opens a dedicated filtered view displaying the matching subscriber cards with search capabilities.
4. Establish clear back navigation: pressing the back button or hardware back from a filtered list always returns the reseller to the six status cards screen, while tapping the "Subscribers" bottom navigation tab restores the default main view.
5. Remove the redundant floating action button (+) to eliminate UI clutter and maintain a cohesive design.

## User Stories

1. As a reseller, I want to see the total number of Active Users at a glance, so that I understand how many total paying subscribers currently hold valid service.
2. As a reseller, I want to see the count of Online Users currently connected, so that I can gauge active real-time network usage.
3. As a reseller, I want to see the count of Offline Users whose subscriptions are active but have no active network session, so that I can proactively troubleshoot disconnected lines and customer routers.
4. As a reseller, I want to see the count of Users Expiring Soon (within 48 hours), so that I can remind subscribers before their internet disconnects.
5. As a reseller, I want to see the count of Recently Expired Users (expired within the last 7 days), so that I can reach out to renew their subscriptions while they are still recent.
6. As a reseller, I want to see the count of all Expired Users across all time, so that I know the total historical lapsed subscriber base.
7. As a reseller, I want to tap the Active Users card, so that I can view a list containing solely active subscribers.
8. As a reseller, I want to tap the Online Users card, so that I can see the exact list of subscribers currently authenticated on the network.
9. As a reseller, I want to tap the Offline Users card, so that I can immediately identify and contact disconnected customers who are paying for active service.
10. As a reseller, I want to tap the Users Expiring Soon card, so that I can see who needs immediate renewal within the next 48 hours.
11. As a reseller, I want to tap the Recently Expired Users card, so that I can see subscribers whose service ended within the past 7 days.
12. As a reseller, I want to tap the Expired Users card, so that I can browse all expired subscribers.
13. As a reseller, I want to search by name, username, or phone number within any filtered list, so that I can quickly find specific individuals in that category.
14. As a reseller, I want to tap any subscriber card in a filtered list, so that I can view their complete account details, balance, and operational actions.
15. As a reseller, I want pressing the back button from a filtered subscriber list to return me directly to the six status cards, so that I can check other status categories without being kicked out to the main screen or exiting the app.
16. As a reseller, I want tapping the "Subscribers" bottom navigation tab from anywhere to return me to the default dashboard view, so that I can easily reset my view to all subscribers.
17. As a reseller, I want the status cards to compute immediately from cached data when offline, so that I can view my subscriber counts even without an internet connection.
18. As a reseller, I want the status screen to be free of redundant floating buttons, so that the interface remains clean, modern, and aligned with the app design.

## Implementation Decisions

1. **Category Definitions and Domain Logic:**
   - *Active Users*: Valid, non-expired subscription (`!isExpired`).
   - *Online Users*: Network connection session alive on gateway (`isOnline`, where online status contains "online").
   - *Offline Users*: Valid active subscription without an active connection session (`!isExpired && !isOnline`).
   - *Users Expiring Soon*: Active subscription expiring in 2 days (48 hours) or less (`diffMs in 0..(2 days)`).
   - *Recently Expired Users*: Subscription ended within the last 7 days (`now - expTimestamp in 0..(7 days)`).
   - *Expired Users*: Subscription period has elapsed (`isExpired`).

2. **In-Memory Calculation:**
   - Subscriber counts and filtering are computed in-memory from the combined list of gateway subscribers and local accounts. This avoids redundant network roundtrips, ensures zero latency when opening the screen, and provides full offline functionality.

3. **Status Grid Layout and Card Interactivity:**
   - The status panel organizes the six cards into a 2-column, 3-row grid:
     - Row 1: Active Users & Online Users
     - Row 2: Offline Users & Users Expiring Soon
     - Row 3: Recently Expired Users & Expired Users
   - Each card is interactive, responding to click events with ripple feedback and triggering navigation to the filtered category view.
   - The floating action button (+) is removed entirely.

4. **Dedicated Filtered View and Navigation Stack:**
   - A dedicated destination route is introduced for category-filtered subscribers.
   - The view renders the category title, subscriber count capsule, search pill, and standard subscriber cards.
   - Standard back navigation pops back to the six status cards screen.
   - The global bottom navigation bar remains accessible, allowing direct return to the primary dashboard.

## Testing Decisions

- A good test verifies external business logic and observable contracts without depending on internal UI layout mechanics or private fields.
- **Seam Selection:**
  - *Primary Seam (JVM Domain Level)*: A headless unit test verifying the subscriber status classification and partitioning oracle. This exercises the exact boundary rules (e.g. 48-hour threshold for expiring soon, 7-day threshold for recently expired, active offline vs expired offline) against diverse subscriber fixture vectors.
  - *Secondary Seam (UI / Navigation Flow)*: Verification that card clicks invoke navigation with the expected category key and that back navigation pops the back-stack appropriately.
- **Prior Art:**
  - Existing JVM tests in `app/src/test/` verifying date parsing, balance calculation, and repository filters.

## Out of Scope

- Modifying the primary dashboard filtering options or changing the persistent search screen.
- Triggering batch operations (e.g. bulk renewals or mass messaging) from the filtered list.
- Modifying EarthLink gateway API query schemas or server-side filtering endpoints.

## Further Notes

- All terminology adheres strictly to `CONTEXT.md`.
- No new third-party dependencies are required; all UI components reuse the existing design tokens, icons, and subscriber card composables.
