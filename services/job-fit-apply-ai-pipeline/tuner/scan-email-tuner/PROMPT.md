Use the ScanEmailTuner source-of-truth skill at /Users/dkkyai/projects/job-fit-apply-ai/services/job-fit-apply-ai-pipeline/tuner/scan-email-tuner/SCAN_EMAIL_TUNER_SKILL.md and run the ScanEmailTuner workflow against the dataset in /Users/dkkyai/projects/job-fit-apply-ai/services/job-fit-apply-ai-pipeline/tuner/scan-email-tuner/data-set.

Follow that skill as the canonical instructions. Iterate on /Users/dkkyai/projects/job-fit-apply-ai/services/job-fit-apply-ai-pipeline/src/main/kotlin/com/jd/pipeline/nodes/ScanEmailNode.kt and /Users/dkkyai/projects/job-fit-apply-ai/services/job-fit-apply-ai-pipeline/src/main/resources/skills/SCAN_SKILL.md until the dataset is scanning correctly.

Use --max-iterations 5 unless I specify a different limit. Run verification before finishing and summarize:
- which dataset files pass
- which still fail or are partial
- what changed in ScanEmailNode.kt and SCAN_SKILL.md