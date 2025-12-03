# Tasks: Bi-Directional ASTM Workflow Support

**Input**: Design documents from `/specs/001-bi-directional-astm/`  
**Prerequisites**: plan.md ✅, spec.md ✅, research.md ✅, data-model.md ✅, contracts/ ✅  
**Tests**: REQUIRED per Constitution Principle IV (TDD)  
**Organization**: Tasks grouped by user story for independent implementation and testing

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3)
- Include exact file paths in descriptions

## Path Conventions

- **Main application**: `src/main/java/org/itech/ahb/`
- **Protocol library**: `astm-http-lib/src/main/java/org/itech/ahb/lib/`
- **Tests**: `src/test/java/org/itech/ahb/`
- **Configuration**: Repository root (`configuration.yml`, `README.md`)

---

## Phase 1: Setup (Project Preparation)

**Purpose**: Prepare development environment and verify existing infrastructure

- [ ] T001 Clone ASTM Mock Server for testing: `git clone https://github.com/DIGI-UW/astm-mock-server.git tools/astm-mock-server`
- [ ] T002 [P] Verify mock server runs: `cd tools/astm-mock-server && pip install -r requirements.txt && python server.py --help`
- [ ] T003 [P] Verify bridge builds: `mvn clean compile` in repository root
- [ ] T004 Create test resources directory: `src/test/resources/`

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Core infrastructure changes that MUST be complete before user story implementation

**⚠️ CRITICAL**: No user story implementation can begin until this phase is complete

- [ ] T005 Update `ASTMHandler` interface to add `sourceIp` parameter with default method for backward compatibility in `astm-http-lib/src/main/java/org/itech/ahb/lib/astm/handling/ASTMHandler.java`
- [ ] T006 Update `ASTMHandlerService.handle()` signature to accept `sourceIp` and add backward-compatible overload in `astm-http-lib/src/main/java/org/itech/ahb/lib/astm/handling/ASTMHandlerService.java`
- [ ] T007 Verify all `ASTMHandler` implementations compile with updated interface (may need signature updates)

**Checkpoint**: Handler interface ready - user story implementation can now begin

---

## Phase 3: User Story 1 - Source Analyzer Identification (Priority: P1) 🎯 MVP

**Goal**: Enable OpenELIS to identify which analyzer sent a message via `X-Source-Analyzer-IP` header

**Independent Test**: Send ASTM message through bridge using mock server push mode, verify HTTP request to OpenELIS contains `X-Source-Analyzer-IP` header with correct IP

### Tests for User Story 1 ⚠️

> **NOTE: Write these tests FIRST, ensure they FAIL before implementation**

- [ ] T008 [P] [US1] Unit test for source IP extraction (IPv4) in `src/test/java/org/itech/ahb/astm/handling/SourceIPExtractionTest.java`
- [ ] T008b [P] [US1] Unit test for source IP extraction (IPv6 addresses like `2001:db8::1`, `::1`) in `src/test/java/org/itech/ahb/astm/handling/SourceIPExtractionTest.java` (FR-003)
- [ ] T009 [P] [US1] Unit test for `X-Source-Analyzer-IP` header addition in `src/test/java/org/itech/ahb/http/handling/HTTPForwardingHeaderTest.java`
- [ ] T010 [P] [US1] Integration test for multi-analyzer header verification in `src/test/java/org/itech/ahb/integration/MultiAnalyzerIPTest.java`

### Implementation for User Story 1

- [ ] T011 [US1] Add `extractSourceIp(Socket socket)` private method in `astm-http-lib/src/main/java/org/itech/ahb/lib/astm/handling/ASTMReceiveThread.java`
- [ ] T012 [US1] Call `extractSourceIp()` in `run()` method and pass to `astmHandlerService.handle()` in `astm-http-lib/src/main/java/org/itech/ahb/lib/astm/handling/ASTMReceiveThread.java`
- [ ] T013 [US1] Add `X-Source-Analyzer-IP` header to HTTP request builder when `sourceIp` is not null in `astm-http-lib/src/main/java/org/itech/ahb/lib/http/handling/DefaultForwardingASTMToHTTPHandler.java`
- [ ] T014 [US1] Add debug logging for IP extraction success and header addition in `DefaultForwardingASTMToHTTPHandler.java`
- [ ] T015 [US1] Add warning logging when IP extraction fails (null socket, closed socket, null address) in `ASTMReceiveThread.java`
- [ ] T016 [US1] Verify tests pass: `mvn test -Dtest=SourceIPExtractionTest,HTTPForwardingHeaderTest,MultiAnalyzerIPTest` (includes IPv4 and IPv6 tests)

### Manual Verification for User Story 1

- [ ] T017 [US1] Test with mock server push mode: verify `X-Source-Analyzer-IP` header appears in bridge logs (see `quickstart.md`)
- [ ] T018 [US1] Test with 3 mock server instances: verify each message has correct source IP (TS-002 from spec)

**Checkpoint**: User Story 1 complete - analyzer messages include source IP header. MVP deliverable! ✅

---

## Phase 4: User Story 2 - Query Flow Verification (Priority: P2)

**Goal**: Verify existing HTTP→ASTM query capability works correctly with documentation

**Independent Test**: Send HTTP POST with `forwardAddress` and `forwardPort` params to bridge, mock server receives query and responds with field list

### Tests for User Story 2 ⚠️

> **NOTE: Write verification tests, ensure they PASS (verifying existing functionality)**

- [ ] T019 [P] [US2] Integration test for query response flow (TS-003) in `src/test/java/org/itech/ahb/integration/QueryFlowTest.java`
- [ ] T019b [P] [US2] Integration test for retry logic: verify retries when analyzer temporarily unavailable (FR-010) in `src/test/java/org/itech/ahb/integration/RetryLogicTest.java`
- [ ] T020 [P] [US2] Integration test for line contention handling (TS-004) in `src/test/java/org/itech/ahb/integration/LineContentionTest.java`
- [ ] T021 [P] [US2] Integration test for protocol version selection (TS-005) in `src/test/java/org/itech/ahb/integration/ProtocolVersionTest.java`

### Verification for User Story 2

- [ ] T022 [US2] Verify `forwardAddress` and `forwardPort` parameters work in `src/main/java/org/itech/ahb/controller/HTTPListenController.java` (read-only verification)
- [ ] T023 [US2] Verify line contention handling in `astm-http-lib/src/main/java/org/itech/ahb/lib/http/handling/DefaultForwardingHTTPToASTMHandler.java` (read-only verification)
- [ ] T024 [US2] Verify tests pass: `mvn test -Dtest=QueryFlowTest,RetryLogicTest,LineContentionTest,ProtocolVersionTest`

### Manual Verification for User Story 2

- [ ] T025 [US2] Test query flow with mock server: `curl -X POST "http://localhost:8443/?forwardAddress=localhost&forwardPort=5000" -d "H|\^&|||"` (see `quickstart.md`)
- [ ] T026 [US2] Test line contention with mock server: `python test_communication.py --host localhost --port 12001 --mode query`

**Checkpoint**: User Story 2 complete - query flow verified and tested

---

## Phase 5: User Story 3 - Configuration and Documentation (Priority: P3)

**Goal**: Enable administrators to deploy and configure bridge using only documentation

**Independent Test**: Follow README to deploy bridge, configure for OpenELIS, verify bi-directional communication

### Documentation Tasks for User Story 3

- [ ] T027 [P] [US3] Add architecture overview section to `README.md` with bi-directional flow diagram
- [ ] T028 [P] [US3] Add multi-analyzer setup instructions to `README.md`
- [ ] T029 [P] [US3] Add `X-Source-Analyzer-IP` header documentation to `README.md`
- [ ] T030 [P] [US3] Add troubleshooting section to `README.md`
- [ ] T031 [P] [US3] Update configuration examples in `README.md` with correct Spring Boot property paths
- [ ] T032 [US3] Verify `configuration.yml` structure matches Spring Boot property names per FR-011

### Verification for User Story 3

- [ ] T033 [US3] Validate README by following it to deploy fresh bridge instance (SC-003: under 30 minutes)
- [ ] T034 [US3] Validate all configuration examples work without modification

**Checkpoint**: User Story 3 complete - documentation enables self-service deployment

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: Final improvements affecting multiple user stories

- [ ] T035 [P] Run full test suite: `mvn test`
- [ ] T036 [P] Verify no regressions in existing functionality
- [ ] T037 [P] Update `docs/BRIDGE_DOCUMENTATION_IMPROVEMENTS.md` to mark completed items
- [ ] T038 Create Docker Compose test environment file `docker-compose.test.yml` per `quickstart.md`
- [ ] T039 Run `quickstart.md` validation end-to-end
- [ ] T040 Update spec status to "Complete" in `/specs/001-bi-directional-astm/spec.md`

---

## Dependencies & Execution Order

### Phase Dependencies

```
Phase 1 (Setup)
    ↓
Phase 2 (Foundational) ← BLOCKS all user stories
    ↓
    ├─→ Phase 3 (US1) ← MVP, can start immediately after Phase 2
    │       ↓
    ├─→ Phase 4 (US2) ← Can run in parallel with US1 (verification only)
    │       ↓
    └─→ Phase 5 (US3) ← Can run in parallel with US1/US2 (documentation)
            ↓
        Phase 6 (Polish) ← After all user stories complete
```

### User Story Dependencies

| Story | Can Start After | Dependencies on Other Stories |
|-------|-----------------|------------------------------|
| US1 (Source IP) | Phase 2 complete | None - independent |
| US2 (Query Flow) | Phase 2 complete | None - independent verification |
| US3 (Documentation) | Phase 2 complete | None - can document before implementation complete |

### Within User Story 1

1. Tests (T008, T008b, T009, T010) - Write FIRST, must FAIL
2. Core implementation (T011-T013) - Makes tests pass
3. Logging (T014-T015) - Polish
4. Verification (T016-T018) - Confirm complete

---

## Parallel Opportunities

### Phase 1 (All parallel)

```bash
# Can run simultaneously:
T002: Verify mock server
T003: Verify bridge builds
```

### Phase 3: User Story 1 (Tests parallel)

```bash
# Launch all US1 tests together:
T008: SourceIPExtractionTest.java (IPv4)
T008b: SourceIPExtractionTest.java (IPv6)
T009: HTTPForwardingHeaderTest.java
T010: MultiAnalyzerIPTest.java
```

### Phase 4: User Story 2 (Tests parallel)

```bash
# Launch all US2 tests together:
T019: QueryFlowTest.java
T019b: RetryLogicTest.java
T020: LineContentionTest.java
T021: ProtocolVersionTest.java
```

### Phase 5: User Story 3 (All parallel)

```bash
# All documentation tasks can run in parallel:
T027: Architecture overview
T028: Multi-analyzer setup
T029: Header documentation
T030: Troubleshooting section
T031: Configuration examples
```

### Cross-Story Parallelization

After Phase 2 completes, all three user stories can be worked on in parallel:
- Developer A: User Story 1 (implementation)
- Developer B: User Story 2 (verification testing)
- Developer C: User Story 3 (documentation)

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup (~30 min)
2. Complete Phase 2: Foundational (~1 hour)
3. Complete Phase 3: User Story 1 (~4 hours)
4. **STOP and VALIDATE**: Test with ASTM Mock Server
5. Deploy MVP if successful ✅

### Incremental Delivery

```
Setup + Foundational → Foundation ready
    ↓
Add User Story 1 → Test → Deploy (MVP!)
    ↓
Add User Story 2 → Test → Deploy (Query verified)
    ↓
Add User Story 3 → Validate → Deploy (Documented)
    ↓
Polish → Final validation → Release
```

### Time Estimates

| Phase | Estimated Time | Cumulative |
|-------|---------------|------------|
| Phase 1: Setup | 30 min | 30 min |
| Phase 2: Foundational | 1 hour | 1.5 hours |
| Phase 3: User Story 1 | 4 hours | 5.5 hours |
| Phase 4: User Story 2 | 2 hours | 7.5 hours |
| Phase 5: User Story 3 | 3 hours | 10.5 hours |
| Phase 6: Polish | 1.5 hours | **12 hours total** |

---

## Notes

- [P] tasks = different files, no dependencies
- [Story] label maps task to specific user story for traceability
- Each user story is independently completable and testable
- TDD required per Constitution Principle IV: write tests first, verify they fail
- Tests use ASTM Mock Server per spec testing strategy
- Commit after each task or logical group
- Stop at any checkpoint to validate story independently
- Constitution compliance verified in plan.md - all principles pass

