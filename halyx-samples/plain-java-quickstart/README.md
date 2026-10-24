# Plain Java quickstart

Halyx without Spring. `Quickstart.main` builds a `Halyx` on the in-memory provider, then

- writes a blob and reads it back,
- publishes a message and receives it,
- writes a parameter and reads it back.

`QuickstartTest` runs the same steps and checks the values.

## Run it

```
mvn -pl halyx-samples/plain-java-quickstart -am test
mvn -pl halyx-samples/plain-java-quickstart -am install -DskipTests
mvn -pl halyx-samples/plain-java-quickstart exec:java -Dexec.mainClass=dev.halyx.samples.quickstart.Quickstart
```

No cloud, account or container is needed: the default provider is `memory`.

## Switch the cloud

The provider is configuration, not code. Pass system properties (add them after `exec:java`, or to `java -D...`):

| Property | Meaning | Default |
|---|---|---|
| `quickstart.provider` | `memory`, `aws`, `azure` or `gcp` | `memory` |
| `quickstart.provider.region` | the provider's region | the cloud's default |
| `quickstart.provider.endpoint` | an emulator, such as LocalStack | none |
| `quickstart.provider.property.<name>` | a provider setting, such as `azure.storage.account` | none |
| `quickstart.bucket` | bucket, container or Cloud Storage bucket | `quickstart-bucket` |
| `quickstart.topic` | SNS topic, Service Bus topic or Pub/Sub topic | `orders` |
| `quickstart.subscription` | SQS queue, `<topic>/subscriptions/<name>` or Pub/Sub subscription | `orders-billing` |
| `quickstart.parameter-location` | App Configuration endpoint or Parameter Manager location | none |

Credentials come from each cloud's default chain; see [Provider configuration](https://halyx.dev/docs/providers.html).
Halyx doesn't create the bucket, topic or subscription: create them first. The sample writes the blob
`greetings/hello.txt` and the parameter `quickstart/greeting`.

AWS:

```
-Dquickstart.provider=aws -Dquickstart.provider.region=eu-west-2 \
-Dquickstart.bucket=acme-quickstart -Dquickstart.topic=orders -Dquickstart.subscription=orders-billing
```

Azure:

```
-Dquickstart.provider=azure \
-Dquickstart.provider.property.azure.storage.account=acmefiles \
-Dquickstart.provider.property.azure.servicebus.namespace=acme.servicebus.windows.net \
-Dquickstart.bucket=quickstart -Dquickstart.topic=orders -Dquickstart.subscription=orders/subscriptions/billing \
-Dquickstart.parameter-location=https://acme.azconfig.io
```

Google Cloud:

```
-Dquickstart.provider=gcp -Dquickstart.provider.property.gcp.project-id=acme-prod \
-Dquickstart.bucket=acme-quickstart -Dquickstart.topic=orders -Dquickstart.subscription=orders-billing
```

Only the configuration changes; the code is the same on every provider.
