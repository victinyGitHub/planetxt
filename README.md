# Planetxt – Nearby Boarding-Pass Broadcaster

This app lets an **Admin** device broadcast boarding-pass info over Google Nearby Connections so that **User** devices receive **only the row that matches their credentials**.

---

## CSV Import Format
Upload a plain-text **comma-separated** (`.csv`) file via the *Import CSV & Broadcast* button on the Admin panel.

Each **line** must follow **exactly this layout** (no header row):

```
last_name,booking_reference,boarding_pass_payload
```

Field details
1. **last_name** – Passenger last name (case-sensitive, no commas).
2. **booking_reference** – Six-character airline PNR / booking code (no commas).
3. **boarding_pass_payload** – Anything you want to send to that passenger (seat, gate, JSON, etc.).  It may itself contain additional commas; the app treats **everything after the second comma as the payload**.

Example:
```
Smith,ABC123,Gate A12,Seat 14C,Group 2
Doe,Z9X8Y7,Lounge access voucher
```
• The first row will be encrypted with key derived from `Smith|ABC123` and broadcast.  Users who enter that last name + booking-ref will decrypt and see `Gate A12,Seat 14C,Group 2`.
• The second row similarly targets `Doe|Z9X8Y7`.

Notes & limitations
- No quoting or escaped commas are supported – keep last-name and booking-reference free of commas.
- Blank lines are ignored; extra columns after the third are concatenated into the payload with commas preserved.
- File must be selectable by Android’s Storage Access Framework (any MIME type of `text/*` works).
- Admin can still send ad-hoc messages manually; leave the credential fields empty to broadcast *unencrypted* to everyone.

---
### Security recap
The payload is wrapped with **AES-256-GCM** using a key derived via PBKDF2-SHA256 from `last_name‖booking_reference` (100 k iterations).  Only peers that know the matching credentials can decrypt their line; others discard the ciphertext.
