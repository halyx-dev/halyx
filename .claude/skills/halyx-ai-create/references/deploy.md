# Deployment pieces

Only what the user asked for. None of it is proven until the service has run on the cloud: say so in the report.

## Image

`mvn spring-boot:build-image` builds a buildpack image. Set `JAVA_TOOL_OPTIONS=-XX:MaxDirectMemorySize=64M` (or
more) wherever it runs: the buildpack's memory calculator otherwise caps direct memory at 10 MB, which the cloud
SDKs' Netty clients exceed under load.

## Helm chart

Copy `halyx-samples/portable-orders/deploy/helm/portable-orders` and rename it. It deploys one image on EKS, GKE and
AKS; per-cloud values carry what differs:

| Value | Holds |
|---|---|
| `image.repository`, `image.tag` | the registry the cluster pulls from |
| `profiles` | the cloud and the features, such as `[gcp, events, cache]` |
| `env` | the `<SERVICE>_*` resource names, `AWS_REGION` or `GOOGLE_CLOUD_PROJECT` |
| `config` | extra Spring YAML mounted as an `application.yml`, for what environment variables can't express (provider `properties`) |
| `files` | files mounted for the service, such as a Memorystore CA certificate |
| `serviceAccount.annotations`, `podLabels` | the workload identity binding |
| `javaToolOptions` | `-XX:MaxDirectMemorySize=64M` by default |

Write a `values-aws.yaml`, `values-azure.yaml` and `values-gcp.yaml` with each cloud's profile, names and identity.
Probes use `/actuator/health/liveness` and `/readiness`.

## Workload identity

No keys in the cluster. Each cloud's default credential chain finds the platform's identity:

| Cluster | Mechanism | The chart sets |
|---|---|---|
| EKS | EKS Pod Identity: an association of the namespace and service account with an IAM role (or IRSA) | nothing for Pod Identity; `eks.amazonaws.com/role-arn` for IRSA |
| GKE | Workload Identity Federation: the Kubernetes service account acts as a Google service account (`roles/iam.workloadIdentityUser` on it) | `iam.gke.io/gcp-service-account: <email>` |
| AKS | Microsoft Entra Workload ID: a user-assigned managed identity with a federated credential for the service account | `azure.workload.identity/client-id: <client id>`, and the pod label `azure.workload.identity/use: "true"` |

## Permissions per resource

Scope each grant to the service's resources. The AWS and Azure columns are what the sample's identity holds on EKS
and AKS (`cloud-testing/infra/aws/k8s/main.tf`, `cloud-testing/infra/azure/k8s/main.tf`); the Google Cloud column
lists the predefined roles that cover the same calls.

| Halyx resource | AWS (IAM actions) | Azure (roles, on the resource) | Google Cloud (roles, on the resource) |
|---|---|---|---|
| Blob store | `s3:GetObject`, `PutObject`, `DeleteObject` on the objects; `s3:ListBucket` on the bucket (health lists one item) | Storage Blob Data Contributor | `roles/storage.objectUser` |
| Document store | `dynamodb:GetItem`, `PutItem`, `UpdateItem`, `DeleteItem`, `Query`, `BatchGetItem`, `BatchWriteItem`, `ConditionCheckItem`, `DescribeTable`, on the table and its indexes | Cosmos DB Built-in Data Contributor (a Cosmos DB SQL role assignment, not an Azure role) | `roles/datastore.user` |
| Topic (publish) | `sns:Publish`; `sns:ListTopics` if topics are named rather than given as ARNs | Azure Service Bus Data Sender | `roles/pubsub.publisher` on the topic |
| Subscription (listen) | `sqs:ReceiveMessage`, `DeleteMessage`, `ChangeMessageVisibility`, `GetQueueUrl`, `GetQueueAttributes`; the queue policy lets the topic or rule send | Azure Service Bus Data Receiver | `roles/pubsub.subscriber` on the subscription |
| Event bus (publish) | `events:PutEvents` | EventGrid Data Sender on the namespace | `roles/eventarc.messageBusUser` on the bus |
| Event subscription (listen) | as a subscription: the rule's target queue | EventGrid Data Receiver on the namespace | as a subscription: the pipeline's Pub/Sub subscription |
| Stream producer and consumer | `kafka-cluster:Connect`, and the topic and group actions used (`WriteData`, `ReadData`, `DescribeTopic`, `AlterGroup`, `DescribeGroup`) | Azure Event Hubs Data Sender and Data Receiver | `roles/managedkafka.client` |
| Cache | `elasticache:Connect` on the replication group and the IAM user | a Redis data access policy assignment for the identity's object id | `roles/memorystore.dbConnectionUser` |
| Database | `rds-db:connect` on `dbuser:<resource id>/<user>` | the identity as an Entra ID database user (or admin) | `roles/cloudsql.client` and `roles/cloudsql.instanceUser`; an IAM database user |
| Parameter store, parameters | `ssm:GetParameter`, `GetParameters`, `GetParametersByPath` on the prefix | App Configuration Data Reader | `roles/parametermanager.parameterAccessor` |
| Function | `lambda:InvokeFunction` on the function (and its qualifier, `function:<name>:<alias>`) | a function key from Key Vault (Key Vault Secrets User covers reading it), or the identity allowed by App Service Authentication's Entra ID app | `roles/run.invoker` on the function's Cloud Run service |
| Parameter store, secrets | `secretsmanager:GetSecretValue`, `DescribeSecret` on `secret:<prefix>/*`; `ListSecrets` and `BatchGetSecretValue` on `*` for config imports with reload | Key Vault Secrets User | `roles/secretmanager.secretAccessor`; `roles/secretmanager.viewer` to list them for config imports with reload |

- **Health reads a probe name.** Each parameter store's health check reads `<prefix>/halyx-ping` and expects "not
  found". A grant on single secrets returns "access denied" instead, and health goes DOWN: grant the whole prefix.
- **Writes.** Add the write actions only where the service writes: `ssm:PutParameter`,
  `secretsmanager:PutSecretValue` and `CreateSecret`, App Configuration Data Owner, Key Vault Secrets Officer.
- **Presigned URLs on Google Cloud** need the service account to sign: `roles/iam.serviceAccountTokenCreator` on
  itself.
- **Eventarc pipelines** publish to Pub/Sub with an OAuth token: name a service account that can publish to the
  topic in the destination's authentication, and give the Eventarc service agent
  `roles/iam.serviceAccountTokenCreator` on it.
- The Google Cloud column hasn't been run at least privilege by the Halyx tests (their identity holds broader
  roles): verify it on the first deployment, and narrow or widen from what fails.

## Infrastructure as code

If asked, write Terraform per cloud from the resources in the plan, with names matching the defaults in
`application.yml`. `cloud-testing/infra/{aws,azure,gcp}/k8s/main.tf` in the Halyx repository are complete, working
examples for every resource the sample uses, including the clusters, workload identity and the grants above.
Required settings that are easy to miss:
- **DynamoDB:** string `pk` (and `sk`) key attributes; a global secondary index per declared index, named as it is,
  on its partition and sort attributes; TTL on `halyx_expires` if the service writes with expiry.
- **Firestore:** a composite index per declared index (collection group, on its partition and sort attributes);
  the emulator doesn't need one, so only a real deployment shows it missing.
- **Cosmos DB:** a container partitioned on `/pk` with default TTL -1; local (key) auth off, Entra ID only.
- **SNS to SQS:** `RawMessageDelivery`, and a queue policy allowing the topic.
- **EventBridge:** the rule's event pattern, the queue as target, and a queue policy allowing the rule.
- **Service Bus:** each subscription under its topic; the subscription's `name` is `<topic>/subscriptions/<name>`.
- **Event Grid:** a namespace topic and a queue (pull) event subscription; the endpoint goes in
  `properties.azure.eventgrid.endpoint`.
- **Eventarc Advanced:** a bus, an enrollment with a CEL match, and a pipeline to a Pub/Sub topic.
- **Memorystore:** Private Service Connect, IAM auth and in-transit encryption; the chart mounts the CA file
  (`files`) and sets `halyx.caches.<key>.properties.redis.ca-certificate` to its path (`config`).
- **Functions:** deployed with the function's own tooling, per cloud; the service's Terraform only grants the
  invoke. On Azure, either store a function key (`azurerm_function_app_host_keys`, or a function-level key) as a Key
  Vault secret, or turn on App Service Authentication with an Entra ID app whose audience the service names.
- **Cloud SQL:** IAM database authentication on, an IAM user, and the connector's instance connection name.
