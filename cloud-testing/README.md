# Cloud testing

Everything needed to run Halyx' tests outside the unit and emulator tests the build runs.

| Path | What it is |
|---|---|
| `scripts/aws-real-cloud.sh`, `scripts/azure-real-cloud.sh`, `scripts/gcp-real-cloud.sh` | Real-cloud runs, one tier at a time (`TIER=1` to `4`): provision with Terraform, run the tests, always tear down |
| `scripts/azure-setup-test-principal.sh` | One-time setup of the Azure test service principal |
| `scripts/in-vpc-runner.sh` | Runs a test suite on a VM inside a VPC, for services with no public endpoint; sourced by the cloud scripts |
| `scripts/vault-kubernetes-it.sh` | Vault's Kubernetes login against a real minikube cluster |
| `infra/<cloud>/tier<n>/` | The Terraform for each cloud and tier |
| `docker-compose.yml` | The Floci emulators, for manual experiments (the integration tests start their own containers) |

Run the scripts from the repository root, for example:

```sh
TIER=2 AWS_PROFILE=default AWS_REGION=ap-southeast-2 cloud-testing/scripts/aws-real-cloud.sh
docker compose -f cloud-testing/docker-compose.yml up -d
```

What each tier covers, the identities and permissions it needs, and the latest results are in
[Testing on real clouds](../docs/cloud-testing/index.md).
