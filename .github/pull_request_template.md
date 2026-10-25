## What this changes

<!-- What the change does and why. Link the issue it fixes, such as "Fixes #12".
     Fixes go to the maintenance branch of the oldest line they apply to, such as 1.0.x; features go to main.
     See https://github.com/halyx-dev/halyx/blob/main/CONTRIBUTING.md#branches -->


## How it was tested

<!-- Unit tests, conformance kits, emulators, Spring Boot 3.5 and 4. -->

## Checklist

- [ ] Behaviour is the same on every provider, or the difference is declared as a capability
- [ ] Tests cover the change, in the conformance kit where it's portable behaviour
- [ ] Documentation in `docs/` is updated if users see the change
- [ ] New files have the license header (`mvn -N com.mycila:license-maven-plugin:format`)
- [ ] Follows the [code conventions](https://github.com/halyx-dev/halyx/blob/main/CONTRIBUTING.md#code-conventions); `mvn validate` passes
