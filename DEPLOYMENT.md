# Deploying Chalkline

The goal: a link people open, with nothing to install.

Chalkline is one container plus one PostgreSQL database. That is deliberately
boring, because boring is what you can still run in two years.

| | |
|---|---|
| **Start here** | [DEPLOY-SELF-HOSTED.md](DEPLOY-SELF-HOSTED.md) if it is going on a university server |
| **Cloud** | [Azure](DEPLOY-AZURE.md) &middot; [AWS](DEPLOY-AWS.md) &middot; [Google Cloud](DEPLOY-GOOGLE-CLOUD.md) |

---

## Three decisions before you deploy anything

### 1. Do not keep real data in the default H2 file

With no `DATABASE_URL` set, the app writes to `./data` next to itself. On most
cloud platforms that disk is wiped on every deploy and every restart, so a
term's timetables would vanish with no warning. Create a managed PostgreSQL
database and point `DATABASE_URL` at it. Every guide here does this.

### 2. Decide whether this serves one institution or many

**One institution** (a university hosting it for itself):

```
ALLOW_SIGN_UP=false
```

Nobody can create a new institution. You create yours once, then turn it off.
Leave the Stripe variables empty: billing stays switched off and everyone has
the full free plan.

**Many institutions** (running it as a product):

```
ALLOW_SIGN_UP=true
```

Plus the Stripe setup below.

### 3. Set PUBLIC_URL to the real address

Share links, calendar subscription URLs and Stripe return URLs are all built
from `PUBLIC_URL`. Leave it as localhost and you will publish links to students
that point at a machine they cannot reach.

---

## What it costs

Rough monthly figures for a small deployment, at the time of writing. All three
clouds change pricing, so treat these as the shape rather than a quote.

| | Compute | Database | Roughly |
|---|---|---|---|
| **Google Cloud Run** | Scales to zero when idle | Cloud SQL `db-f1-micro` | **Cheapest** if traffic is bursty, because you pay nothing overnight |
| **Azure Container Apps** | Monthly free grant covers a small app | PostgreSQL Flexible Server B1ms | **Comparable**, and easiest if the university is already Microsoft |
| **AWS App Runner** | Billed while provisioned | RDS `db.t4g.micro` | **Most expensive** of the three for something this small |
| **One university VM** | Already paid for | Same box, via Docker Compose | **Effectively free** |

A cold start on a scale-to-zero platform is roughly 5 to 15 seconds for a
Spring Boot app. Lecturers read that as "broken". If it will be used daily,
either keep one instance warm or accept the extra few pounds a month.

**Cheapest sensible answer for a student running this for one university: put
it on a VM the university already pays for, with Docker Compose.**

---

## Turning on billing

Only needed if you are charging other institutions. Skip it entirely otherwise.

Card details never reach Chalkline. The customer is sent to a page hosted by
Stripe and comes back afterwards, which keeps this code out of PCI scope.

1. Create a Stripe account and stay in **test mode** until it all works.
2. **Products** &rarr; add a product, give it a **recurring monthly price** &rarr;
   copy the price id (`price_...`).
3. **Developers &rarr; API keys** &rarr; copy the secret key (`sk_test_...`).
4. **Developers &rarr; Webhooks** &rarr; **Add endpoint**:
   - URL: `https://your-domain/billing/webhook`
   - Events: `checkout.session.completed`, `customer.subscription.created`,
     `customer.subscription.updated`, `customer.subscription.deleted`
   - Copy the signing secret (`whsec_...`).
5. Set the variables:

```
STRIPE_SECRET_KEY=sk_test_...
STRIPE_PRICE_ID=price_...
STRIPE_WEBHOOK_SECRET=whsec_...
```

6. Test with Stripe's card `4242 4242 4242 4242`, any future expiry, any CVC.
7. When it works, swap the test keys for live ones.

**The webhook secret is not optional.** Without it, incoming webhooks are
rejected outright, because an unverified webhook is an open invitation for
anyone to upgrade themselves for free.

Entitlement is decided by webhooks, never by the browser returning from
checkout. A success URL is just a URL and anyone can visit it.

---

## Checklist before real people use it

- [ ] `DATABASE_URL` points at managed PostgreSQL, not the H2 file
- [ ] `SPRING_PROFILES_ACTIVE=prod`
- [ ] `PUBLIC_URL` is the real public address, with `https://`
- [ ] `SESSION_COOKIE_SECURE=true` (the `prod` profile does this)
- [ ] `ALLOW_SIGN_UP` matches what you actually want
- [ ] `ALLOW_MANUAL_UPGRADE=false` in production
- [ ] `DEMO_ORG_EMAIL` is empty, or the demo account has a real password
- [ ] **Database backups are on and you have restored one at least once.**
      An untested backup is not a backup.
- [ ] Health check path set to `/actuator/health`
- [ ] You have opened the site on a phone

---

## Things that will bite

**The database URL format.** Platforms hand you
`postgres://user:pass@host:5432/db`. That is not a JDBC URL. Rewrite it as
`jdbc:postgresql://host:5432/db` and put the credentials in
`DATABASE_USERNAME` and `DATABASE_PASSWORD`. This is far and away the most
common cause of a failed first deploy.

**Out of memory on a small instance.** The `Dockerfile` sets
`-XX:MaxRAMPercentage=75`, which keeps the JVM inside a 512 MB container. If you
change the memory limit, leave that flag alone.

**Schema changes on deploy.** `DATABASE_DDL_AUTO=update` lets Hibernate alter
tables to match the code. It will add a column; it will not rename or delete
one, and it cannot undo anything. Take a backup before deploying a change to an
entity class.

**Stripe webhooks in local testing.** Your laptop is not reachable from the
internet. Use the Stripe CLI:

```bash
stripe listen --forward-to localhost:8080/billing/webhook
```

It prints a `whsec_...` to use as `STRIPE_WEBHOOK_SECRET` while testing.

**Time zones.** Timestamps are stored in UTC. The grid itself is labels, so
nothing shifts. Calendar feeds use floating local time on purpose: a 09:00 class
stays at 09:00 wherever it is opened and does not move when the clocks change.
