# OC2 Sample Capabilities

This repository is the source home for independently buildable OC2 sample Native capability modules. It is not a runtime input and its contents grant no product authority.

## Modules

Each module is standalone and produces exactly one selected JAR for one capability:

| Module | Pack ID | Selected JAR |
| --- | --- | --- |
| `it-support-guide` | `oc2.it-support-guide` | `oc2-it-support-guide-pack-1.0.0.jar` |
| `travel-consultant` | `oc2.travel-consultant` | `oc2-travel-consultant-pack-1.0.0.jar` |
| `university-advisor` | `oc2.university-advisor` | `oc2-university-advisor-pack-1.0.0.jar` |

Build a module from its directory with `mvn test package`, or build the three existing modules from the repository root with `mvn test package`. An aggregate build is a developer convenience only: its output is never a capability artifact.

## Governance

A future sample Native Agent, Skill, or Tool requires its own approved delivery Story, static logical definition, static execution binding, selected artifact and entrypoint, artifact identity/version/digest validation, and proportionate Pack and Host tests. A module, its source, tests, aggregate output, or an unbound JAR is not routable or executable merely because it exists here.

This repository does not permit discovery, scanning, upload, hot loading, runtime registry mutation, or shared artifact authority. Every binding selects one artifact for one logical capability. A shared artifact requires a separately approved decision and Story.
