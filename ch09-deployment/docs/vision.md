# Vision: Trailhead Order Integration

## Mission

Orders from partners and from the Peakmart marketplace reach Trailhead Gear's order database and warehouse without
anyone retyping, fixing or chasing them. Today the integration team handles them with scripts and by hand: orders get
lost, some are shipped twice, and nobody can say quickly where a given order is. The order integration takes every
order from the channel it arrives on, checks it, completes it with product data, records it once, and hands it to the
warehouse, and it tells the sender when an order can't be accepted.

## Target users

- Partner shops, which send orders from their own systems and need to know at once whether an order was accepted
- Peakmart, the marketplace, which publishes its orders as events and expects nothing back
- The warehouse team, which needs complete pick lists it can work from without asking questions
- The order desk, which answers customers and partners and needs to find any order and see what happened to it

## Goals

- Every accepted order reaches the warehouse as a pick list within 15 minutes of arriving
- No order is shipped twice because it arrived twice
- Every rejected order is reported to its sender with reasons a person can act on
- The order desk can find any order of the last 90 days by its order number and see its state in under a minute

## Scope

### In scope

- Partner orders over HTTP, answered synchronously with accepted or rejected
- Peakmart orders from Peakmart's order topic
- Checking every order against the same rules, whichever channel it came from
- Completing order lines with product name, price and weight from the product catalog
- Recording each order once in the order database
- Pick lists to the warehouse
- Rejection emails to partners
- A way for the order desk to see an order's state

### Out of scope

- Web shop orders: the web shop writes to the order database itself
- Payments, refunds and returns
- Stock reservation: the warehouse system does it
- Changing or cancelling an order after it was accepted (a later release)

## Constraints

- Apache Camel 4.22 LTS on Spring Boot 4.1 and Java 21, the integration team's standard
- Peakmart owns the order topic and its message format; Trailhead can't change either
- The warehouse takes pick lists only as files on its SFTP server
- The order database is the team's PostgreSQL 16 instance
- Customer names, emails and addresses are personal data under GDPR

## Success measures

- Manual order handling at the order desk drops from about 40 orders a day to fewer than 5
- No duplicate shipments in the first three months after go-live
- The order desk answers "where is order X?" without asking the integration team
