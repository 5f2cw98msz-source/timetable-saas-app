# Deploying to Microsoft Azure

Azure Container Apps plus Azure Database for PostgreSQL.

Container Apps has a monthly free grant that covers a small application, and
scales to zero if you let it. The easiest option if the university is already
a Microsoft institution, because the sign-on integration later lives in the
same tenant.

**Before you start:** read the three decisions at the top of
[DEPLOYMENT.md](DEPLOYMENT.md).

You need the [Azure CLI](https://learn.microsoft.com/cli/azure/install-azure-cli)
and an Azure subscription. Every command below is copy-and-paste; change the
values in the first block only.

---

## 1. Set your variables

```bash
RG=chalkline-rg
LOCATION=uksouth
APP=chalkline
DB_SERVER=chalkline-db-$RANDOM        # must be globally unique
DB_NAME=chalkline
DB_USER=chalkadmin
DB_PASSWORD='ChangeThis_Str0ng!'      # use your own
```

Keep this terminal open. If you close it, set them again before continuing.

---

## 2. Sign in and create the resource group

```bash
az login
az group create --name $RG --location $LOCATION
```

---

## 3. Create the database

```bash
az postgres flexible-server create \
  --resource-group $RG \
  --name $DB_SERVER \
  --location $LOCATION \
  --admin-user $DB_USER \
  --admin-password "$DB_PASSWORD" \
  --sku-name Standard_B1ms \
  --tier Burstable \
  --storage-size 32 \
  --version 16 \
  --public-access 0.0.0.0 \
  --yes

az postgres flexible-server db create \
  --resource-group $RG \
  --server-name $DB_SERVER \
  --database-name $DB_NAME
```

`--public-access 0.0.0.0` allows connections from Azure services only, not from
the whole internet. Adding your own IP for admin access:

```bash
az postgres flexible-server firewall-rule create \
  --resource-group $RG --name $DB_SERVER \
  --rule-name my-laptop \
  --start-ip-address $(curl -s ifconfig.me) \
  --end-ip-address $(curl -s ifconfig.me)
```

**Turn on backups now, not later:**

```bash
az postgres flexible-server update \
  --resource-group $RG --name $DB_SERVER \
  --backup-retention 14
```

---

## 4. Create the Container Apps environment

```bash
az extension add --name containerapp --upgrade
az provider register --namespace Microsoft.App --wait
az provider register --namespace Microsoft.OperationalInsights --wait

az containerapp env create \
  --name $APP-env \
  --resource-group $RG \
  --location $LOCATION
```

---

## 5. Build and deploy

Run this from the repository root. Azure builds the `Dockerfile` for you, so
you do not need Docker installed locally.

```bash
az containerapp up \
  --name $APP \
  --resource-group $RG \
  --environment $APP-env \
  --source . \
  --ingress external \
  --target-port 8080
```

The first build takes a few minutes. It prints the public URL when it finishes.
Save it:

```bash
APP_URL=https://$(az containerapp show --name $APP --resource-group $RG \
  --query properties.configuration.ingress.fqdn -o tsv)
echo $APP_URL
```

---

## 6. Configure it

Secrets go in Container Apps secrets, not in plain environment variables, so
they do not show in the portal or in `az containerapp show`.

```bash
az containerapp secret set \
  --name $APP --resource-group $RG \
  --secrets db-password="$DB_PASSWORD"

az containerapp update \
  --name $APP --resource-group $RG \
  --set-env-vars \
    SPRING_PROFILES_ACTIVE=prod \
    DATABASE_URL="jdbc:postgresql://$DB_SERVER.postgres.database.azure.com:5432/$DB_NAME?sslmode=require" \
    DATABASE_USERNAME="$DB_USER" \
    DATABASE_PASSWORD=secretref:db-password \
    PUBLIC_URL="$APP_URL" \
    SUPPORT_EMAIL="timetable@your-university.edu" \
    ALLOW_SIGN_UP=false
```

Note `?sslmode=require`. Azure PostgreSQL refuses unencrypted connections, and
without it the app will fail to start with a connection error.

---

## 7. Health checks and scaling

```bash
az containerapp update \
  --name $APP --resource-group $RG \
  --min-replicas 1 --max-replicas 3
```

`--min-replicas 0` costs less but gives every first visitor a cold start of
roughly 5 to 15 seconds. For something staff open daily, 1 is the right answer.

---

## 8. Check it

```bash
curl $APP_URL/actuator/health     # {"status":"UP"}
open $APP_URL
```

Create your institution through **Start free**. Then, if this serves one
university only, confirm `ALLOW_SIGN_UP=false` is set so nobody else can.

---

## Looking at the logs

```bash
az containerapp logs show --name $APP --resource-group $RG --follow
```

---

## Deploying a change

```bash
az containerapp up \
  --name $APP --resource-group $RG \
  --environment $APP-env --source .
```

Take a database backup first if the change touches an entity class.

---

## A custom domain

```bash
az containerapp hostname add \
  --hostname timetable.your-university.edu \
  --name $APP --resource-group $RG

az containerapp hostname bind \
  --hostname timetable.your-university.edu \
  --name $APP --resource-group $RG --validation-method CNAME
```

Then update `PUBLIC_URL` to the new address, or every share link you have
already published will keep pointing at the old one.

---

## Roughly what it costs

| | |
|---|---|
| Container Apps | Free grant covers a small app; beyond it, a few pounds a month |
| PostgreSQL B1ms burstable | Around GBP 12 to 20 per month |
| Storage and egress | Pennies at this size |

Azure gives new accounts a starting credit, and students can often get an
education subscription with credit included.

---

## Tearing it all down

```bash
az group delete --name $RG --yes --no-wait
```

This deletes the database too. Take a backup first if you want to keep anything.
