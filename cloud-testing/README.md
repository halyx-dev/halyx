# Cloud testing

Everything needed to run Halyx' tests outside the unit and emulator tests the build runs.

| Path | What it is |
|---|---|
| `scripts/aws-real-cloud.sh`, `scripts/azure-real-cloud.sh`, `scripts/gcp-real-cloud.sh` | Real-cloud runs, one tier at a time (`TIER=1` to `4`): provision with Terraform, run the tests, always tear down |
| `scripts/eks-real-cloud.sh`, `scripts/gke-real-cloud.sh`, `scripts/aks-real-cloud.sh` | The portable orders service on a throwaway EKS, GKE or AKS cluster, with its end-to-end suite on Spring Boot 4 and 3.5; `scripts/k8s-portable-orders.sh` holds what they share |
| `scripts/azure-setup-test-principal.sh` | One-time setup of the Azure test service principal |
| `scripts/in-vpc-runner.sh` | Runs a test suite on a VM inside a VPC, for services with no public endpoint; sourced by the cloud scripts |
| `scripts/vault-kubernetes-it.sh` | Vault's Kubernetes login against a real minikube cluster |
| `infra/<cloud>/tier<n>/` | The Terraform for each cloud and tier |
| `infra/<cloud>/k8s/` | The Terraform for each cloud's cluster run; `infra/aws/k8s/test-identity-policy.json` is the extra IAM the EKS run needs |
| `reports/` | The end-to-end reports of the cluster runs (not committed) |
| `docker-compose.yml` | The Floci emulators, for manual experiments (the integration tests start their own containers) |

Run the scripts from the repository root, for example:

```sh
TIER=2 AWS_PROFILE=default AWS_REGION=ap-southeast-2 cloud-testing/scripts/aws-real-cloud.sh
docker compose -f cloud-testing/docker-compose.yml up -d
```

What each tier covers, the identities and permissions it needs, and the latest results are in
[Testing on real clouds](../docs/cloud-testing/index.md).
