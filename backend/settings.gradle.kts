rootProject.name = "devflow-backend"

// Module layout — enforces ARCHITECTURE.md Section 10 module boundaries at build time.
//
//   common                shared kernel: event contract (Section 4), base entities. Depends on nothing.
//   <module>-api          the module's explicitly exposed public interface. May depend on :common only.
//   <module>-impl         internal implementation. May depend on :common, its own -api, and other
//                         modules' -api projects — NEVER another module's -impl.
//   app                   bootstrap/deployable. The only project allowed to depend on -impl modules.
//
// Adding an `implementation(project(":X-impl"))` line in another module's build file is a
// deliberate architecture violation — treat it as a failing review, per ARCHITECTURE.md Section 10.
include("app", "common")
include("auth-api", "auth-impl")
include("board-api", "board-impl")
include("gitci-api", "gitci-impl")
include("ai-api", "ai-impl")
include("notification-api", "notification-impl")
