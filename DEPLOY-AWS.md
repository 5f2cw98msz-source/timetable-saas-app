# Deploying to AWS

App Runner plus RDS for PostgreSQL.

App Runner is the closest AWS gets to "give it a Dockerfile and go". It is the
most expensive of the three clouds for something this small, because it bills
while the instance is provisioned rather than per request, but it is the least
AWS-shaped thing to operate.

**Before you start:** read the three decisions at the top of
[DEPLOYMENT.md](DEPLOYMENT.md).

You need the [AWS CLI](https://docs.aws.amazon.com/cli/latest/userguide/getting-started-install.html),
Docker installed locally, and an AWS account.

---

## 1. Set your variables

```bash
REGION=eu-west-2                      # London
APP=chalkline
DB_INSTANCE=chalkline-db
DB_NAME=chalkline
DB_USER=chalkline
DB_PASSWORD='ChangeThis_Str0ng!'      # use your own
ACCOUNT=$(aws sts get-caller-identity --query Account --output text)
```

---

## 2. Sign in

```bash
aws configure        # key, secret, region, output format
aws sts get-caller-identity
```

---

## 3. Create the database

```bash
aws rds create-db-instance \
  --region $REGION \
  --db-instance-identifier $DB_INSTANCE \
  --db-instance-class db.t4g.micro \
  --engine postgres \
  --engine-version 16 \
  --master-username $DB_USER \
  --master-user-password "$DB_PASSWORD" \
  --allocated-storage 20 \
  --db-name $DB_NAME \
  --backup-retention-period 14 \
  --publicly-accessible \
  --no-multi-az
```

`--backup-retention-period 14` turns on daily backups. A value of 0 turns them
off, which is the default on some paths, so set it explicitly.

Creating the instance takes 5 to 10 minutes.

```bash
aws rds wait db-instance-available \
  --db-instance-identifier $DB_INSTANCE --region $REGION

DB_HOST=$(aws rds describe-db-instances \
  --db-instance-identifier $DB_INSTANCE --region $REGION \
  --query 'DBInstances[0].Endpoint.Address' --output text)
echo $DB_HOST
```

### Let App Runner reach it

`--publicly-accessible` gives it a public endpoint, but the security group still
blocks everything. For a first deployment, open PostgreSQL to your own IP and to
AWS:

```bash
SG=$(aws rds describe-db-instances \
  --db-instance-identifier $DB_INSTANCE --region $REGION \
  --query 'DBInstances[0].VpcSecurityGroups[0].VpcSecurityGroupId' --output text)

MY_IP=$(curl -s ifconfig.me)
aws ec2 authorize-security-group-ingress --region $REGION \
  --group-id $SG --protocol tcp --port 5432 --cidr $MY_IP/32
```

> **This is the part to come back to.** Reaching RDS from App Runner properly
> means a VPC connector, which is more setup than belongs in a first deploy.
> Until then the database is protected by its password alone. Do not put real
> student data behind that: add a VPC connector, or use the self-hosted route
> in [DEPLOY-SELF-HOSTED.md](DEPLOY-SELF-HOSTED.md) where the database is not
> exposed at all.

---

## 4. Push the image to ECR

```bash
aws ecr create-repository --repository-name $APP --region $REGION

aws ecr get-login-password --region $REGION \
  | docker login --username AWS --password-stdin $ACCOUNT.dkr.ecr.$REGION.amazonaws.com

docker build -t $APP .
docker tag $APP:latest $ACCOUNT.dkr.ecr.$REGION.amazonaws.com/$APP:latest
docker push $ACCOUNT.dkr.ecr.$REGION.amazonaws.com/$APP:latest
```

On an Apple Silicon Mac, build for the architecture App Runner runs:

```bash
docker build --platform linux/amd64 -t $APP .
```

Skipping that gives an image that pushes fine and then fails to start.

---

## 5. Create the App Runner service

Write the configuration to a file, because the inline form of this command is
unreadable:

```bash
cat > /tmp/apprunner.json <<JSON
{
  "ServiceName": "$APP",
  "SourceConfiguration": {
    "AuthenticationConfiguration": {
      "AccessRoleArn": "arn:aws:iam::$ACCOUNT:role/service-role/AppRunnerECRAccessRole"
    },
    "AutoDeploymentsEnabled": false,
    "ImageRepository": {
      "ImageIdentifier": "$ACCOUNT.dkr.ecr.$REGION.amazonaws.com/$APP:latest",
      "ImageRepositoryType": "ECR",
      "ImageConfiguration": {
        "Port": "8080",
        "RuntimeEnvironmentVariables": {
          "SPRING_PROFILES_ACTIVE": "prod",
          "DATABASE_URL": "jdbc:postgresql://$DB_HOST:5432/$DB_NAME",
          "DATABASE_USERNAME": "$DB_USER",
          "DATABASE_PASSWORD": "$DB_PASSWORD",
          "SUPPORT_EMAIL": "timetable@your-university.edu",
          "ALLOW_SIGN_UP": "false"
        }
      }
    }
  },
  "InstanceConfiguration": { "Cpu": "1024", "Memory": "2048" },
  "HealthCheckConfiguration": {
    "Protocol": "HTTP",
    "Path": "/actuator/health",
    "Interval": 10,
    "Timeout": 5,
    "HealthyThreshold": 1,
    "UnhealthyThreshold": 5
  }
}
JSON

aws apprunner create-service --region $REGION --cli-input-json file:///tmp/apprunner.json
```

If the ECR access role does not exist yet, create the service once in the
console instead; it offers to create the role for you, and the CLI works from
then on.

**Move the password to Secrets Manager before you go live.** In the block above
it sits in the service configuration in plain text, which is fine for a first
deploy and not fine afterwards.

---

## 6. Tell it its own address

```bash
APP_URL=https://$(aws apprunner list-services --region $REGION \
  --query "ServiceSummaryList[?ServiceName=='$APP'].ServiceUrl" --output text)
echo $APP_URL
```

Then set `PUBLIC_URL` to that value and update the service, either in the
console under **Configuration &rarr; Environment variables** or with
`aws apprunner update-service`.

---

## 7. Check it

```bash
curl $APP_URL/actuator/health     # {"status":"UP"}
open $APP_URL
```

---

## Logs

```bash
aws logs tail /aws/apprunner/$APP --follow --region $REGION
```

---

## Deploying a change

```bash
docker build --platform linux/amd64 -t $APP .
docker tag $APP:latest $ACCOUNT.dkr.ecr.$REGION.amazonaws.com/$APP:latest
docker push $ACCOUNT.dkr.ecr.$REGION.amazonaws.com/$APP:latest

aws apprunner start-deployment --region $REGION \
  --service-arn $(aws apprunner list-services --region $REGION \
    --query "ServiceSummaryList[?ServiceName=='$APP'].ServiceArn" --output text)
```

---

## Roughly what it costs

| | |
|---|---|
| App Runner, 1 vCPU / 2 GB | Around GBP 25 to 40 per month while provisioned |
| RDS `db.t4g.micro` | Around GBP 12 to 18 per month |
| ECR storage | Pennies |

App Runner can pause a service when it is idle, which cuts the compute cost but
adds a cold start. AWS gives new accounts a free tier that covers RDS
`db.t4g.micro` for the first twelve months.

If cost is the deciding factor, Google Cloud Run is cheaper for this shape of
application, and a university VM is cheaper than all of them.

---

## Tearing it down

```bash
aws apprunner delete-service --region $REGION --service-arn <arn>
aws rds delete-db-instance --db-instance-identifier $DB_INSTANCE \
  --skip-final-snapshot --region $REGION
aws ecr delete-repository --repository-name $APP --force --region $REGION
```

`--skip-final-snapshot` throws the data away. Drop it if you want a snapshot
kept.
