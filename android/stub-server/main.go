// Payment stub: honors Idempotency-Key and counts real charges.
// Run:  go run . [-addr :8080] [-chaos timeout-after-commit]
package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"log"
	"net/http"
	"sync"
	"time"
)

type chargeReq struct {
	QuoteID     string `json:"quoteId"`
	AmountMinor int64  `json:"amountMinor"`
}
type chargeRes struct {
	ChargeID    string `json:"chargeId"`
	Status      string `json:"status"`
	AmountMinor int64  `json:"amountMinor"`
}
type record struct {
	req chargeReq
	res chargeRes
}

var (
	mu      sync.Mutex
	records = map[string]record{} // Idempotency-Key -> first committed charge
	charges int
)

func writeJSON(w http.ResponseWriter, code int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	_ = json.NewEncoder(w).Encode(v)
}

func main() {
	addr := flag.String("addr", "127.0.0.1:8080", "listen address")
	chaos := flag.String("chaos", "", "timeout-after-commit: commit the charge, then never answer (first attempt per key only)")
	flag.Parse()
	mux := http.NewServeMux()

	mux.HandleFunc("/quote", func(w http.ResponseWriter, r *http.Request) {
		amount, tenor := int64(12_000_000), 6
		fmt.Sscan(r.URL.Query().Get("amount"), &amount)
		fmt.Sscan(r.URL.Query().Get("tenor"), &tenor)
		if amount <= 0 || tenor < 1 {
			writeJSON(w, 400, map[string]string{"error": "invalid_quote_request"})
			return
		}
		total := amount + amount*12*int64(tenor)/1000 // 1.2%/month fee
		writeJSON(w, 200, map[string]any{"quoteId": "q_1", "version": 1, "amountMinor": amount,
			"tenorMonths": tenor, "instalmentMinor": total / int64(tenor)})
	})

	mux.HandleFunc("/checkout", func(w http.ResponseWriter, r *http.Request) {
		key := r.Header.Get("Idempotency-Key")
		var req chargeReq
		if r.Method != http.MethodPost {
			writeJSON(w, 405, map[string]string{"error": "method_not_allowed"})
			return
		} else if key == "" || json.NewDecoder(r.Body).Decode(&req) != nil {
			writeJSON(w, 400, map[string]string{"error": "missing_key_or_bad_body"})
			return
		}
		mu.Lock() // check-and-commit is atomic: concurrent duplicates cannot both charge
		if rec, ok := records[key]; ok {
			mu.Unlock()
			if rec.req != req { // same key, different payload = client bug
				writeJSON(w, 422, map[string]string{"error": "idempotency_key_reuse"})
				return
			}
			log.Printf("REPLAY key=%s req=%s charge=%s", key, r.Header.Get("X-Request-Id"), rec.res.ChargeID)
			w.Header().Set("Idempotent-Replay", "true")
			writeJSON(w, 200, rec.res)
			return
		}
		charges++
		rec := record{req, chargeRes{fmt.Sprintf("ch_%04d", charges), "CONFIRMED", req.AmountMinor}}
		records[key] = rec
		mu.Unlock()
		log.Printf("CHARGE key=%s req=%s charge=%s total=%d", key, r.Header.Get("X-Request-Id"), rec.res.ChargeID, charges)
		if *chaos == "timeout-after-commit" {
			time.Sleep(30 * time.Second) // money moved; client never hears about it
			return
		}
		writeJSON(w, 200, rec.res)
	})

	mux.HandleFunc("/stats", func(w http.ResponseWriter, _ *http.Request) {
		mu.Lock()
		defer mu.Unlock()
		writeJSON(w, 200, map[string]int{"chargeCount": charges, "uniqueKeys": len(records)})
	})
	mux.HandleFunc("/reset", func(w http.ResponseWriter, _ *http.Request) {
		mu.Lock()
		records, charges = map[string]record{}, 0
		mu.Unlock()
		w.WriteHeader(204)
	})

	log.Printf("stub listening on %s chaos=%q", *addr, *chaos)
	log.Fatal(http.ListenAndServe(*addr, mux))
}