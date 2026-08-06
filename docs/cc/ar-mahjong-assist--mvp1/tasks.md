---
slugid: ar-mahjong-assist--mvp1
stage: tasks
date: 2026-08-05
plan: docs/cc/ar-mahjong-assist--mvp1/plan.md
---

# Tasks

## Unit 1: 补齐首批 workflow 文档
- **Status**: completed
- **Execution note**: direct -- docs-only
- **Files**: `docs/cc/ar-mahjong-assist--mvp1/README.md`, `docs/cc/ar-mahjong-assist--mvp1/sources.md`, `docs/cc/ar-mahjong-assist--mvp1/spec.md`, `docs/cc/ar-mahjong-assist--mvp1/tech.md`
- **Depends on**: 无
- **Result**: Created the four requested workflow documents and verified UTF-8 text after sync.
- **Commit**: 未提交

### Todos
| Todo | Status | Evidence |
|------|--------|----------|
| Read plan and source documents | completed | Used `plan.md`, origin docs, protocol/handoff docs and current code snippets |
| Draft README.md | completed | `docs/cc/ar-mahjong-assist--mvp1/README.md` |
| Draft sources.md | completed | `docs/cc/ar-mahjong-assist--mvp1/sources.md` |
| Draft spec.md | completed | `docs/cc/ar-mahjong-assist--mvp1/spec.md` |
| Draft tech.md | completed | `docs/cc/ar-mahjong-assist--mvp1/tech.md` |
| Sync to WSL project | completed | Wrote files under `/home/caolx/CA_proj/armahjongassist-develop/docs/cc/ar-mahjong-assist--mvp1/` |
| Verify UTF-8 readability | completed | Checked expected Chinese text exists and mojibake marker is absent |

### Attempts
#### Attempt 1
- **Subagent status**: INLINE_DONE
- **Summary**: Completed docs-only implementation inline because files are tightly coupled and no runtime behavior changed.
- **Tests**: Not run; docs-only change. UTF-8 content check passed.
- **Files changed**: `README.md`, `sources.md`, `spec.md`, `tech.md`, `tasks.md`
- **Concerns**: `review.md`, `decisions.md`, `risks.md`, and `qa.md` remain planned follow-up documents.

## Unit 2: Apply document-review decisions
- **Status**: completed
- **Execution note**: direct -- docs-only
- **Files**: `docs/cc/ar-mahjong-assist--mvp1/README.md`, `docs/cc/ar-mahjong-assist--mvp1/sources.md`, `docs/cc/ar-mahjong-assist--mvp1/spec.md`, `docs/cc/ar-mahjong-assist--mvp1/tech.md`, `docs/cc/ar-mahjong-assist--mvp1/decisions.md`, `docs/cc/ar-mahjong-assist--mvp1/review.md`
- **Depends on**: Unit 1
- **Result**: Incorporated user decisions from document review and created decisions/review records.
- **Commit**: 未提交

### Todos
| Todo | Status | Evidence |
|------|--------|----------|
| Record target user decision | completed | `spec.md`, `decisions.md` |
| Record low-confidence interaction decision | completed | `spec.md`, `tech.md`, `decisions.md` |
| Record QR local-debug boundary | completed | `spec.md`, `tech.md`, `decisions.md` |
| Narrow advice claim | completed | `spec.md`, `tech.md` |
| Create review.md | completed | `docs/cc/ar-mahjong-assist--mvp1/review.md` |
| Create decisions.md | completed | `docs/cc/ar-mahjong-assist--mvp1/decisions.md` |

### Attempts
#### Attempt 1
- **Subagent status**: INLINE_DONE
- **Summary**: Applied document-review decisions inline because this was docs-only and based on explicit user guidance.
- **Tests**: Not run; docs-only change. UTF-8 content check passed before sync.
- **Files changed**: `README.md`, `sources.md`, `spec.md`, `tech.md`, `decisions.md`, `review.md`, `tasks.md`
- **Concerns**: `risks.md` and `qa.md` remain follow-up documents.

## Unit 3: Record Guangdong Mahjong rule decision
- **Status**: completed
- **Execution note**: direct -- docs-only
- **Files**: `docs/cc/ar-mahjong-assist--mvp1/README.md`, `docs/cc/ar-mahjong-assist--mvp1/spec.md`, `docs/cc/ar-mahjong-assist--mvp1/tech.md`, `docs/cc/ar-mahjong-assist--mvp1/decisions.md`, `docs/cc/ar-mahjong-assist--mvp1/review.md`
- **Depends on**: Unit 2
- **Result**: Recorded Guangdong Mahjong as MVP1 default rule and reserved a controlled custom-rule extension point.
- **Commit**: 未提交

### Todos
| Todo | Status | Evidence |
|------|--------|----------|
| Add Guangdong Mahjong default to spec | completed | `spec.md` positioning, scope and gates |
| Add rule extension constraints to tech | completed | `tech.md` custom rule upload section |
| Record accepted decisions | completed | `decisions.md` D6/D7 |
| Update review notes | completed | `review.md` applied decisions |

### Attempts
#### Attempt 1
- **Subagent status**: INLINE_DONE
- **Summary**: Applied explicit user decision as docs-only update.
- **Tests**: Not run; docs-only change. UTF-8 content check passed before sync.
- **Files changed**: `README.md`, `spec.md`, `tech.md`, `decisions.md`, `review.md`, `tasks.md`
- **Concerns**: Actual custom-rule schema and upload endpoint remain future implementation work.

## Unit 4: Align rule profile and analyze protocol fields
- **Status**: implemented
- **Execution note**: direct -- narrow cross-stack protocol change
- **Files**: `server/rule_profiles.py`, `server/schemas.py`, `server/main.py`, `server/tests/test_rule_profiles.py`, `server/tests/test_analyze_response_contract.py`, `app/src/main/java/com/example/ai_assist/model/AnalyzeResponse.kt`, `app/src/main/java/com/example/ai_assist/service/GameApiService.kt`, `app/src/main/java/com/example/ai_assist/repository/ChatRepository.kt`, `app/src/main/java/com/example/ai_assist/viewmodel/ChatViewModel.kt`
- **Depends on**: Unit 3
- **Result**: Added Guangdong as the default rule profile, a declarative custom rule profile validator, explicit Android request field `rule_profile=guangdong`, and nullable analyze response fields for `rule_profile`, `recognition_confidence`, and `suggestion_confidence`.
- **Commit**: not submitted

### Todos
| Todo | Status | Evidence |
|------|--------|----------|
| Add rule profile model and validator | completed | `server/rule_profiles.py` |
| Add AnalyzeResponse protocol fields | completed | `server/schemas.py`, `app/src/main/java/com/example/ai_assist/model/AnalyzeResponse.kt` |
| Accept default `rule_profile` in analyze endpoint | completed | `server/main.py` |
| Add controlled custom profile config skeleton | completed | `rule_profile_config` JSON form field with executable-field rejection |
| Send default Android rule profile | completed | `GameApiService.kt`, `ChatRepository.kt` |
| Preserve mapped response metadata | completed | `ChatViewModel.kt` |
| Add contract tests | completed | `server/tests/test_rule_profiles.py`, `server/tests/test_analyze_response_contract.py` |

### Attempts
#### Attempt 1
- **Subagent status**: INLINE_DONE
- **Summary**: Implemented the first engineering slice from the plan: rule profile defaulting/config validation and request/response protocol alignment across server and Android models.
- **Tests**: `python3 -m py_compile server/rule_profiles.py server/schemas.py server/main.py` passed. `/tmp/armahjong-contract-venv/bin/python -m unittest tests.test_rule_profiles tests.test_analyze_response_contract` passed with 6 tests. System Python test run is blocked because project dependencies such as `pydantic` are not installed. Android compile is blocked because WSL has no `java`/`JAVA_HOME`.
- **Files changed**: `rule_profiles.py`, `schemas.py`, `main.py`, `test_rule_profiles.py`, `test_analyze_response_contract.py`, `AnalyzeResponse.kt`, `GameApiService.kt`, `ChatRepository.kt`, `ChatViewModel.kt`, `tasks.md`
- **Concerns**: Confidence values are intentionally nullable placeholders until the recognition/suggestion engines expose real confidence scoring. Custom rules are declarative schema validation only; no rule-engine behavior has been changed yet.
