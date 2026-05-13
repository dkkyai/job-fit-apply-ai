#!/bin/bash
#
# test_jd_bridge.sh — Test script for the jd-bridge API endpoint
#
# This script sends a job posting HTML to the Bridge API and verifies
# the response contains the expected fields (job_id, title, etc.)
#

set -e

# Configuration
BRIDGE_API_URL="${BRIDGE_API_URL:-http://richards-macbook-m1-max.tail02d0e.ts.net:8765}"
API_ENDPOINT="${BRIDGE_API_URL}/api/jobs"
POLL_ENDPOINT="${BRIDGE_API_URL}/api/jobs"

# Polling configuration — aligns with pipeline 600s timeout (120 attempts × 5s = 600s)
POLL_MAX_ATTEMPTS="${POLL_MAX_ATTEMPTS:-120}"
POLL_INTERVAL_SECONDS="${POLL_INTERVAL_SECONDS:-5}"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Test job posting HTML (similar to createTestEnvironment())
JD_HTML='<!DOCTYPE html>
<html>
  <head><title>Senior Mobile Test Engineer - Test Job</title></head>
  <body>
    <div class="jobs-description-content__text">
      <p><strong>Job Description:</strong></p>
      <p>We are looking for a Senior Mobile Test Engineer to join our dynamic QA team. You will be responsible for ensuring the quality and reliability of our mobile applications across iOS and Android platforms. This role involves designing and implementing test strategies, developing automated test frameworks, and mentoring junior team members.</p>
      <p><strong>Responsibilities:</strong></p>
      <ul>
        <li>Design and develop comprehensive test plans for mobile applications</li>
        <li>Implement and maintain automated test scripts using industry-standard frameworks</li>
        <li>Conduct performance testing and analyze results to identify bottlenecks</li>
        <li>Collaborate with development teams to integrate testing into CI/CD pipelines</li>
        <li>Mentor junior QA engineers on best practices and testing methodologies</li>
        <li>Review code changes and provide feedback on testability and quality aspects</li>
        <li>Create detailed test reports and metrics to track quality metrics</li>
      </ul>
      <p><strong>Qualifications:</strong></p>
      <ul>
        <li>Bachelor'\''s degree in Computer Science or related field</li>
        <li>5+ years of experience in mobile testing (iOS and Android)</li>
        <li>Proficiency in test automation frameworks such as Appium, XCUITest, or Espresso</li>
        <li>Strong experience with CI/CD tools like Jenkins, CircleCI, or GitHub Actions</li>
        <li>Excellent problem-solving and analytical skills</li>
        <li>Strong communication skills and ability to work in a collaborative environment</li>
        <li>Experience with Agile methodologies is a plus</li>
      </ul>
    </div>
    <h1 class="jobs-unified-top-card__job-title">Senior Mobile Test Engineer</h1>
    <span class="jobs-unified-top-card__company-name">TestTech Solutions</span>
    <span class="jobs-unified-top-card__bullet">Seattle, WA</span>
  </body>
</html>'

# Functions - use stderr for logging to avoid contaminating command substitution
log_info() {
    echo -e "${GREEN}[INFO]${NC} $1" >&2
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1" >&2
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1" >&2
}

# Check if curl is available
check_dependencies() {
    if ! command -v curl &> /dev/null; then
        log_error "curl is required but not installed"
        exit 1
    fi
    if ! command -v jq &> /dev/null; then
        log_warn "jq is not installed — JSON output will not be formatted"
    fi
}

# Submit job posting to Bridge API
submit_job() {
    local jd_text="$1"
    local role_title="$2"
    local company="$3"
    local location="$4"
    local job_url="${5:-https://example.com/jobs/senior-mobile-test-engineer}"
    local site="${6:-linkedin}"

    log_info "Submitting job posting to Bridge API..."
    log_info "  Role: $role_title"
    log_info "  Company: $company"
    log_info "  Location: $location"

    # Build JSON payload using jq for proper escaping (handles newlines/special chars)
    local payload
    if command -v jq &> /dev/null; then
        payload=$(jq -n \
            --arg jd "$jd_text" \
            --arg title "$role_title" \
            --arg comp "$company" \
            --arg loc "$location" \
            --arg url "$job_url" \
            --arg s "$site" \
            '{jd_text: $jd, role_title: $title, company: $comp, location: $loc, job_url: $url, site: $s}')
    else
        # Fallback: escape newlines manually
        local escaped_jd="${jd_text//$'\n'/\\n}"
        payload=$(cat <<EOF
{"jd_text": "$escaped_jd","role_title": "$role_title","company": "$company","location": "$location","job_url": "$job_url","site": "$site"}
EOF
)
    fi

    # Submit to API
    local response
    response=$(curl -s -w "\n%{http_code}" \
        -X POST "$API_ENDPOINT" \
        -H "Content-Type: application/json" \
        -d "$payload" 2>&1) || {
        log_error "Failed to connect to Bridge API at $API_ENDPOINT"
        log_error "Is the server running?"
        exit 1
    }

    # Extract HTTP status code (last line) and body (all remaining lines)
    local http_code=$(echo "$response" | tail -n1)
    local body
    body=$(echo "$response" | sed '$d')

    echo "$http_code" "$body"
}

# Verify response
verify_response() {
    local http_code="$1"
    local body="$2"

    log_info "Verifying response..."

    # Check HTTP status code (200/201 = sync, 202 = async accepted)
    if [[ "$http_code" != "200" && "$http_code" != "201" && "$http_code" != "202" ]]; then
        log_error "HTTP request failed with status $http_code"
        log_error "Response: $body"
        exit 1
    fi
    log_info "HTTP status: $http_code (accepted) ✓"

    # Check for job_id in response
    if echo "$body" | grep -q '"job_id"'; then
        log_info "Response contains job_id ✓"
    else
        log_error "Response missing job_id field"
        log_error "Response: $body"
        exit 1
    fi

    # Check for status field (if present in response)
    if echo "$body" | grep -q '"status"'; then
        log_info "Response contains status field ✓"
    fi

    # Try to extract job_id
    local job_id
    if command -v jq &> /dev/null; then
        job_id=$(echo "$body" | jq -r '.job_id // empty')
        if [[ -n "$job_id" && "$job_id" != "null" ]]; then
            log_info "Job ID: $job_id"
        fi
    else
        job_id=$(echo "$body" | grep -o '"job_id"[[:space:]]*:[[:space:]]*"[^"]*"' | cut -d'"' -f4)
        if [[ -n "$job_id" ]]; then
            log_info "Job ID: $job_id"
        fi
    fi

    log_info "All verifications passed ✓"
}

# Poll for job completion (optional)
poll_job() {
    local job_id="$1"
    local max_attempts="${POLL_MAX_ATTEMPTS:-120}"
    local poll_interval="${POLL_INTERVAL_SECONDS:-5}"
    local attempt=0

    log_info "Polling for job completion (job_id: $job_id, max_attempts: $max_attempts, interval: ${poll_interval}s)..."

    while [[ $attempt -lt $max_attempts ]]; do
        attempt=$((attempt + 1))

        local response
        response=$(curl -s -w "\n%{http_code}" \
            "$POLL_ENDPOINT/$job_id" 2>&1) || {
            log_warn "Poll request failed, retrying..."
            sleep "$poll_interval"
            continue
        }

        local http_code=$(echo "$response" | tail -n1)
        local body=$(echo "$response" | sed '$d')

        if [[ "$http_code" != "200" ]]; then
            log_warn "Poll returned HTTP $http_code, retrying..."
            sleep "$poll_interval"
            continue
        fi

        # Check status
        local status
        if command -v jq &> /dev/null; then
            status=$(echo "$body" | jq -r '.status // empty')
        else
            status=$(echo "$body" | grep -o '"status"[[:space:]]*:[[:space:]]*"[^"]*"' | cut -d'"' -f4)
        fi

        log_info "Job status: $status"

        if [[ "$status" == "complete" ]]; then
            log_info "Job completed successfully ✓"

            # Verify resume field (nested under artifacts.resume_pdf)
            local resume
            if command -v jq &> /dev/null; then
                resume=$(echo "$body" | jq -r '.artifacts.resume_pdf // empty')
            else
                resume=$(echo "$body" | grep -o '"resume_pdf"[[:space:]]*:[[:space:]]*"[^"]*"' | cut -d'"' -f4)
            fi

            if [[ -n "$resume" && "$resume" != "null" && "$resume" != "" ]]; then
                log_info "Response contains artifacts.resume_pdf field ✓"
            else
                log_error "Response missing artifacts.resume_pdf field"
                return 1
            fi

            # Verify cover_letter field (nested under artifacts.cover_letter_txt)
            local cover_letter
            if command -v jq &> /dev/null; then
                cover_letter=$(echo "$body" | jq -r '.artifacts.cover_letter_txt // empty')
            else
                cover_letter=$(echo "$body" | grep -o '"cover_letter_txt"[[:space:]]*:[[:space:]]*"[^"]*"' | cut -d'"' -f4)
            fi

            if [[ -n "$cover_letter" && "$cover_letter" != "null" && "$cover_letter" != "" ]]; then
                log_info "Response contains artifacts.cover_letter_txt field ✓"
            else
                log_error "Response missing artifacts.cover_letter_txt field"
                return 1
            fi

            # Store the complete response body for artifact download
            echo "$body" > /tmp/jd_bridge_response_${job_id}.json

            return 0
        elif [[ "$status" == "error" ]]; then
            log_error "Job failed with error"
            echo "$body"
            return 1
        fi

        sleep "$poll_interval"
    done

    log_warn "Polling timed out after $max_attempts attempts"
    return 1
}

# Phase 4: Download artifacts
download_artifacts() {
    local job_id="$1"
    local resume_url="${BRIDGE_API_URL}/api/jobs/${job_id}/resume.pdf"
    local cover_url="${BRIDGE_API_URL}/api/jobs/${job_id}/cover_letter.txt"

    log_info "=== Phase 4: Downloading artifacts ==="

    # Download resume PDF
    log_info "Downloading resume PDF from: $resume_url"
    local resume_response
    resume_response=$(curl -s -w "\n%{http_code}" \
        -o /tmp/resume_test_${job_id}.pdf \
        "$resume_url" 2>&1) || {
        log_error "Failed to download resume PDF"
        exit 1
    }

    local resume_code=$(echo "$resume_response" | tail -n1)
    if [[ "$resume_code" != "200" ]]; then
        log_error "Resume download failed with HTTP $resume_code"
        exit 1
    fi
    log_info "Resume PDF downloaded successfully ✓"

    # Check file size
    local resume_size=$(wc -c < /tmp/resume_test_${job_id}.pdf 2>/dev/null || echo "0")
    if [[ "$resume_size" -lt 100 ]]; then
        log_error "Resume PDF file too small ($resume_size bytes)"
        exit 1
    fi
    log_info "Resume PDF size: $resume_size bytes ✓"

    # Download cover letter
    log_info "Downloading cover letter from: $cover_url"
    local cover_response
    cover_response=$(curl -s -w "\n%{http_code}" \
        -o /tmp/cover_test_${job_id}.txt \
        "$cover_url" 2>&1) || {
        log_error "Failed to download cover letter"
        exit 1
    }

    local cover_code=$(echo "$cover_response" | tail -n1)
    if [[ "$cover_code" != "200" ]]; then
        log_error "Cover letter download failed with HTTP $cover_code"
        exit 1
    fi
    log_info "Cover letter downloaded successfully ✓"

    # Check file size
    local cover_size=$(wc -c < /tmp/cover_test_${job_id}.txt 2>/dev/null || echo "0")
    if [[ "$cover_size" -lt 10 ]]; then
        log_error "Cover letter file too small ($cover_size bytes)"
        exit 1
    fi
    log_info "Cover letter size: $cover_size bytes ✓"

    log_info "=== Phase 4 complete: All artifacts downloaded ==="
}

# Phase 5: Validate downloaded artifacts
validate_artifacts() {
    local job_id="$1"

    log_info "=== Phase 5: Validating downloaded artifacts ==="

    # Validate Resume PDF
    log_info "Validating Resume PDF..."

    # Check PDF header
    local pdf_header
    pdf_header=$(head -c 5 /tmp/resume_test_${job_id}.pdf 2>/dev/null)
    if [[ "$pdf_header" != "%PDF-" ]]; then
        log_error "Resume is not a valid PDF (header: '$pdf_header')"
        log_error "Expected: '%PDF-', Got: '$pdf_header'"
        exit 1
    fi
    log_info "Resume PDF header valid (%PDF-) ✓"

    # Check for PDF EOF marker
    if ! grep -q "%%EOF" /tmp/resume_test_${job_id}.pdf 2>/dev/null; then
        log_warn "Resume PDF may be incomplete (no %%EOF marker)"
    else
        log_info "Resume PDF EOF marker present ✓"
    fi

    # Validate Cover Letter
    log_info "Validating Cover Letter..."

    # Check for expected greeting
    if ! grep -q "Dear Hiring Manager" /tmp/cover_test_${job_id}.txt 2>/dev/null; then
        log_error "Cover letter missing expected greeting 'Dear Hiring Manager'"
        log_error "First 200 chars of cover letter:"
        head -c 200 /tmp/cover_test_${job_id}.txt 2>/dev/null
        exit 1
    fi
    log_info "Cover letter contains expected greeting ✓"

    # Verify cover letter is not empty and has reasonable content
    local cover_content
    cover_content=$(cat /tmp/cover_test_${job_id}.txt)
    if [[ ${#cover_content} -lt 50 ]]; then
        log_error "Cover letter seems too short (${#cover_content} chars)"
        exit 1
    fi
    log_info "Cover letter has reasonable content (${#cover_content} chars) ✓"

    log_info "=== Phase 5 complete: All artifacts validated ==="
}

# Optional Phase 6: Verify filesystem storage
verify_filesystem_storage() {
    local job_id="$1"

    log_info "=== Phase 6: Verifying filesystem storage ==="

    # Check common store locations
    local store_dirs=(
        "$HOME/.openclaw/jd-bridge/jobs/${job_id}"
        "/tmp/.openclaw/jd-bridge/jobs/${job_id}"
    )

    local found_dir=""
    for dir in "${store_dirs[@]}"; do
        if [[ -d "$dir" ]]; then
            found_dir="$dir"
            break
        fi
    done

    if [[ -n "$found_dir" ]]; then
        log_info "Found job directory: $found_dir ✓"

        # Check for artifacts
        if [[ -f "$found_dir/resume.pdf" ]]; then
            log_info "Filesystem resume.pdf exists ✓"
            local fs_size
            fs_size=$(wc -c < "$found_dir/resume.pdf" 2>/dev/null || echo "0")
            log_info "Filesystem resume.pdf size: $fs_size bytes ✓"
        else
            log_warn "Filesystem resume.pdf not found in $found_dir"
        fi

        if [[ -f "$found_dir/cover_letter.txt" ]]; then
            log_info "Filesystem cover_letter.txt exists ✓"
        else
            log_warn "Filesystem cover_letter.txt not found in $found_dir"
        fi
    else
        log_warn "Could not find job directory in common locations"
        log_warn "Searched in: ${store_dirs[*]}"
    fi

    log_info "=== Phase 6 complete ==="
}

# Main execution
main() {
    log_info "=== Testing jd-bridge API ==="
    log_info "Endpoint: $API_ENDPOINT"

    check_dependencies

    # Submit the job
    local result
    result=$(submit_job "$JD_HTML" "Senior Mobile Test Engineer" "TestTech Solutions" "Seattle, WA")

    # Parse result
    local http_code=$(echo "$result" | cut -d' ' -f1)
    local body=$(echo "$result" | cut -d' ' -f2-)

    # Verify response
    verify_response "$http_code" "$body"

    # Poll for completion
    local job_id=$(echo "$body" | grep -o '"job_id"[[:space:]]*:[[:space:]]*"[^"]*"' | cut -d'"' -f4)
    local polling_failed=0
    if [[ -n "$job_id" ]]; then
        poll_job "$job_id" || polling_failed=1
    fi

    if [[ "$polling_failed" -eq 0 ]]; then
        # Phase 4: Download artifacts
        download_artifacts "$job_id"

        # Phase 5: Validate artifacts
        validate_artifacts "$job_id"

        # Phase 6: Verify filesystem storage (optional)
        verify_filesystem_storage "$job_id"

        log_info "=== E2E Test completed successfully — all phases passed ==="
        exit 0
    else
        log_error "=== Test failed (polling timed out) ==="
        exit 1
    fi
}

# Run main
main "$@"