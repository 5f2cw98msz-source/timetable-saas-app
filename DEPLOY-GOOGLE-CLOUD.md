# Deploying to Google Cloud

Cloud Run plus Cloud SQL for PostgreSQL.

Usually the cheapest of the three clouds for something this size, because
Cloud Run scales to zero and you pay nothing while nobody is using it. The
trade is a cold start on the first request after a quiet period.

**Before you start:** read the three decisions at the top of
[DEPLOYMENT.md](DEPLOYMENT.md).

You need the [gcloud CLI](https://cloud.google.com/sdk/docs/install) and a
project with billing enabled.

---

## 1. Set your variables

```bash
PROJECT=chalkline-$RANDOM             # must be globally unique
REGION=europe-west2                   # London
APP=chalkline
DB_INSTANCE=chalkline-db
DB_NAME=chalkline
DB_USER=chalkline
DB_PASSWORD='ChangeThis_Str0ng!'      # use your own
```

---

## 2. Create the project and switch on the APIs

```bash
gcloud auth login
gcloud projects create $PROJECT
gcloud config set project $PROJECT
```

Link a billing account in the console, then:

```bash
gcloud services enable \
  run.googleapis.com \
  sqladmin.googleapis.com \
  cloudbuild.googleapis.com \
  secretmanager.googleapis.com
```

---

## 3. Create the database

```bash
gcloud sql instances create $DB_INSTANCE \
  --database-version=POSTGRES_16 \
  --tier=db-f1-micro \
  --region=$REGION \
  --storage-size=10GB \
  --storage-auto-increase \
  --backup-start-time=03:00

gcloud sql databases create $DB_NAME --instance=$DB_INSTANCE
gcloud sql users create $DB_USER --instance=$DB_INSTANCE --password="$DB_PASSWORD"
```

`--backup-start-time` turns on daily backups. Do not leave it out.

Get the connection name, which Cloud Run uses to reach the database over a
socket rather than the public internet:

```bash
CONNECTION_NAME=$(gcloud sql instances describe $DB_INSTANCE \
  --format='value(connectionName)')
echo $CONNECTION_NAME     # project:region:instance
```

---

## 4. Put the password in Secret Manager

```bash
echo -n "$DB_PASSWORD" | gcloud secrets create chalkline-db-password --data-file=-
```

---

## 5. Build and deploy

From the repository root. Cloud Build builds the `Dockerfile` for you.

```bash
gcloud run deploy $APP \
  --source . \
  --region=$REGION \
  --platform=managed \
  --allow-unauthenticated \
  --port=8080 \
  --memory=1Gi \
  --cpu=1 \
  --min-instances=0 \
  --max-instances=4 \
  --add-cloudsql-instances=$CONNECTION_NAME \
  --set-secrets=DATABASE_PASSWORD=chalkline-db-password:latest \
  --set-env-vars="SPRING_PROFILES_ACTIVE=prod,DATABASE_USERNAME=$DB_USER,DATABASE_URL=jdbc:postgresql:///$DB_NAME?cloudSqlInstance=$CONNECTION_NAME&socketFactory=com.google.cloud.sql.postgres.SocketFactory,ALLOW_SIGN_UP=false"
```

The first deploy takes several minutes.

> **The socket factory needs one extra dependency.** The `DATABASE_URL` above
> uses Google's Cloud SQL socket factory, which is not in `pom.xml` by default.
> Either add it:
>
> ```xml
> <dependency>
>   <groupId>com.google.cloud.sql</groupId>
>   <artifactId>postgres-socket-factory</artifactId>
>   <version>1.21.0</version>
> </dependency>
> ```
>
> or skip it and connect over the public IP instead, which is simpler and fine
> to start with. See step 5b.

### 5b. The simpler alternative: public IP

Give the instance a public IP and allow Cloud Run through it.

```bash
DB_IP=$(gcloud sql instances describe $DB_INSTANCE \
  --format='value(ipAddresses[0].ipAddress)')

# Development only: this opens the database to the internet, protected by the
# password alone. Fine while you are testing; move to the socket factory or a
# private IP before real data goes in.
gcloud sql instances patch $DB_INSTANCE \
  --authorized-networks=0.0.0.0/0 --quiet

gcloud run deploy $APP \
  --source . --region=$REGION --platform=managed \
  --allow-unauthenticated --port=8080 --memory=1Gi \
  --set-secrets=DATABASE_PASSWORD=chalkline-db-password:latest \
  --set-env-vars="SPRING_PROFILES_ACTIVE=prod,DATABASE_USERNAME=$DB_USER,DATABASE_URL=jdbc:postgresql://$DB_IP:5432/$DB_NAME,ALLOW_SIGN_UP=false"
```

---

## 6. Tell it its own address

Cloud Run assigns the URL, so `PUBLIC_URL` can only be set after the first
deploy.

```bash
APP_URL=$(gcloud run services describe $APP --region=$REGION \
  --format='value(status.url)')
echo $APP_URL

gcloud run services update $APP --region=$REGION \
  --update-env-vars="PUBLIC_URL=$APP_URL,SUPPORT_EMAIL=timetable@your-university.edu"
```

---

## 7. Check it

```bash
curl $APP_URL/actuator/health     # {"status":"UP"}
open $APP_URL
```

---

## Cold starts

`--min-instances=0` means you pay nothing while it is idle, and the first
request after a quiet period waits 5 to 15 seconds for the JVM to start.

If staff use it daily that will be read as "broken". Keep one warm:

```bash
gcloud run services update $APP --region=$REGION --min-instances=1
```

That costs a few pounds a month and removes the problem entirely.

---

## Logs

```bash
gcloud run services logs tail $APP --region=$REGION
```

---

## Deploying a change

```bash
gcloud run deploy $APP --source . --region=$REGION
```

Cloud Run keeps the previous revision, so rolling back is quick:

```bash
gcloud run revisions list --service=$APP --region=$REGION
gcloud run services update-traffic $APP --region=$REGION --to-revisions=REVISION=100
```

That rolls back the code. It does not roll back a database schema change, which
is why you take a backup before deploying one.

---

## A custom domain

```bash
gcloud run domain-mappings create --service=$APP \
  --domain=timetable.your-university.edu --region=$REGION
```

Add the DNS records it prints, then update `PUBLIC_URL`.

---

## Roughly what it costs

| | |
|---|---|
| Cloud Run, scale to zero | Often within the free tier for a small department |
| Cloud Run, one warm instance | Around GBP 8 to 12 per month |
| Cloud SQL `db-f1-micro` | Around GBP 8 to 12 per month |

Google gives new accounts a starting credit that will cover a good while.

---

## Tearing it down

```bash
gcloud projects delete $PROJECT
```

Deleting the project deletes everything, database included.
