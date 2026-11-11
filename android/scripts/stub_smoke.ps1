$KEY = [guid]::NewGuid().ToString()
$BODY = '{"quoteId":"q_1","amountMinor":12000000}'

# 1. Reset the server state
Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:8080/reset"

# 2. Fire 3 identical checkout requests using the exact same Idempotency-Key
foreach ($i in 1..3) {
    Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:8080/checkout" -Headers @{"Idempotency-Key"=$KEY} -ContentType "application/json" -Body $BODY
}

# 3. Check the stats to verify deduplication
Write-Host "`n--- Server Stats ---"
Invoke-RestMethod -Uri "http://127.0.0.1:8080/stats" | ConvertTo-Json

# 4. Attempt to reuse the key with a tampered payload
Write-Host "`n--- Tampered Payload Test ---"
try {
    Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:8080/checkout" -Headers @{"Idempotency-Key"=$KEY} -ContentType "application/json" -Body '{"quoteId":"q_1","amountMinor":1}'
} catch {
    Write-Host "Rejected with HTTP Status: $($_.Exception.Response.StatusCode.value__)"
}