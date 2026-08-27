# GreenGram AI Development Rules

## Project Context

Before making significant changes, read:

docs/GREEN_GRAM_PROJECT_CONTEXT.md

However, the actual source code, database schema and tests are the source of truth.

The context document may contain outdated information.

Never assume documentation is more accurate than the current implementation.

---

## General Rules

This is an existing Spring Boot e-commerce application approaching Phase 1 deployment.

Do not rewrite or restructure the application unnecessarily.

Do not introduce new frameworks or architectural patterns without a clear reason.

Prefer incremental changes.

Do not modify unrelated functionality while implementing a feature.

---

## Before Implementation

For any non-trivial task:

1. Inspect the relevant existing code.
2. Identify the affected components.
3. Check existing tests.
4. Check database/schema implications.
5. Explain the proposed approach.
6. Identify risks or assumptions.

For significant architectural changes, stop after planning and wait for approval.

---

## Backend

Follow the existing Spring Boot architecture unless there is a demonstrated reason to change it.

Prefer:

Controller
→ Service/Application Layer
→ Repository
→ Database

Keep business logic out of controllers.

Use constructor injection.

Use transactions where multiple related database operations must succeed or fail together.

Do not introduce unnecessary abstractions.

---

## Database

PostgreSQL is the source of truth.

Never perform destructive database operations unless explicitly requested.

Never delete production data.

Do not modify existing migrations casually.

If a schema change is required, explain the migration strategy before implementing it.

Consider:

* Transactions
* Concurrent updates
* Foreign keys
* Constraints
* Indexes
* Historical data

---

## E-commerce Rules

Treat stock, price and order operations as business-critical.

Never change stock behavior without understanding all existing stock modification paths.

Never change order state transitions without checking all existing flows.

Never assume a product price should be read dynamically from the Product entity when creating or displaying historical orders.

Verify the existing implementation before making assumptions.

---

## WhatsApp Integration

Treat WhatsApp/Meta integration as an external system.

Do not expose access tokens or secrets.

Do not change webhook behavior without checking existing verification and message flows.

Handle external API failures explicitly.

---

## Admin Frontend

The admin dashboard is separate from the customer-facing WhatsApp experience.

If implementing React:

* Use TypeScript.
* Prefer React + Vite.
* Keep API communication isolated.
* Do not duplicate backend business logic in the frontend.
* Treat the backend API as the source of truth.
* Do not expose secrets in frontend code.

---

## Testing

For every meaningful backend feature:

* Run existing tests.
* Add tests for new business logic.
* Test validation and failure cases.
* Test transaction-sensitive behavior where appropriate.

Do not remove or weaken tests simply to make the build pass.

---

## Agent Safety

Never:

* Commit secrets
* Expose credentials
* Delete production data
* Drop database tables
* Reset production databases
* Modify deployment configuration without explaining the impact
* Upgrade major dependencies without approval
* Rewrite large portions of the project unnecessarily

---

## Communication

After implementation report:

1. What changed
2. Files changed
3. Why the changes were made
4. Tests added
5. Tests executed
6. Build result
7. Potential risks
8. Recommended next step

For significant changes, show the plan before implementation.
