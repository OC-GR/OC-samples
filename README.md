# OC2 Sample Capabilities

This repository is the source home for independently buildable OC2 sample Native capability modules. It is not a runtime input and its contents grant no product authority.

## Modules

Each module is standalone and produces exactly one selected JAR for one capability:

| Module | Pack ID | Selected JAR |
| --- | --- | --- |
| `it-support-guide` | `oc2.it-support-guide` | `oc2-it-support-guide-pack-1.0.0.jar` |
| `travel-consultant` | `oc2.travel-consultant` | `oc2-travel-consultant-pack-1.0.0.jar` |
| `university-advisor` | `oc2.university-advisor` | `oc2-university-advisor-pack-1.0.0.jar` |
| `aq-health-assistant` | `oc2.aq-health-assistant` | `oc2-aq-health-assistant-pack-1.0.0.jar` |
| `alex-coverage-advisor` | `oc2.alex-coverage-advisor` | `oc2-alex-coverage-advisor-pack-1.0.0.jar` |
| `ebc-benefits-advisor` | `oc2.ebc-benefits-advisor` | `oc2-ebc-benefits-advisor-pack-1.0.0.jar` |

Build a module from its directory with `mvn test package`, or build the modules from the repository root with `mvn test package`. An aggregate build is a developer convenience only: its output is never a capability artifact.

`aq-health-assistant`, `alex-coverage-advisor`, and `ebc-benefits-advisor` are model-driven demo
agents for the model-driven plan-recognition pilot. Each step delegates its reply to the
Host-injected model with a bounded domain prompt aligned to the intake requirements: AQ is health
inquiry and safety triage (non-diagnostic health guidance), Alex is personal-insurance products
and coverage (medical/accident/critical-illness insurance), and EBC is the company group-insurance
employee-benefits lookup (团险: eligibility, limits, balances, validity, rule sources). The Host
owns plan disclosure, step advancement, and unified summary.

## Governance

A future sample Native Agent, Skill, or Tool requires its own approved delivery Story, static logical definition, static execution binding, selected artifact and entrypoint, artifact identity/version/digest validation, and proportionate Pack and Host tests. A module, its source, tests, aggregate output, or an unbound JAR is not routable or executable merely because it exists here.

This repository does not permit discovery, scanning, upload, hot loading, runtime registry mutation, or shared artifact authority. Every binding selects one artifact for one logical capability. A shared artifact requires a separately approved decision and Story.
