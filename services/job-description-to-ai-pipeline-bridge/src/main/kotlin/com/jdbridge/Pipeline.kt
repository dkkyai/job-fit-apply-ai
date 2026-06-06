package com.jdbridge

// The subprocess-based pipeline runner has been removed.
// Processing is now handled by an external worker process that polls
// GET /api/queue/claim and POSTs results back via POST /api/jobs/{id}/result.
