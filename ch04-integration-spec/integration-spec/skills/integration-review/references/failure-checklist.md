# Failure checklist

Ten questions for every step where a message crosses a boundary.

| #   | Question         | Ask                                                                                                  | Answered by, for example |
|-----|------------------|------------------------------------------------------------------------------------------------------|--------------------------|
| Q1  | Duplicate        | Can the same message arrive or be sent twice? What identifies it, and what happens the second time?   | A business rule naming the identifier; an alternative flow for the repeat |
| Q2  | Invalid          | What if the message breaks the schema or a business rule? Who finds out, how, and with what reasons?  | An alternative flow per rejection; the contract's rejection response |
| Q3  | Unavailable      | What if the system we call, or the one we deliver to, is down? For how long do we keep trying, and then what? | An alternative flow; an NFR on recovery; a business rule on how long an order may wait |
| Q4  | Slow             | What if it answers, but late? How long does the caller wait for us, and do we wait for it?            | An NFR on response time; a timeout in the contract |
| Q5  | Partial          | If this step succeeds and a later one fails, what state is left, and is that state allowed?           | A failure postcondition; an alternative flow that resumes |
| Q6  | Order            | Does the order of messages matter here? What if a later message overtakes an earlier one?             | A business rule; "not applicable: each message stands alone" |
| Q7  | Volume           | How many messages, at peak? Does anything in the step limit the rate?                                 | An NFR with a number |
| Q8  | Replay           | Can a person send a rejected or failed message through again after a fix? What prevents a double effect? | A use case for the order desk; a rule on reprocessing |
| Q9  | Traceability     | Can someone see afterwards what happened at this step, for this message?                               | A postcondition that records the outcome; a requirement for a lookup |
| Q10 | Contract         | Is the format at this boundary agreed with its owner, and what happens when the owner changes it?      | A contract in `docs/api/` marked as agreed; an open question to the owner |

Personal data is part of Q9: when the step records or logs a message, which of its fields are personal data, and
how long are they kept? A requirement or constraint on retention answers it.
