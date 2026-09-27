# Real state files

These files were written by earlier builds of this code against a live Stalwart, not
constructed. A file built from the current understanding of the format would only test that
understanding against itself.

- `stale-schema.state`: schema 1, from a build whose bucketing no longer exists. It records two
  defects the current code cannot produce. `$junk` sits in the learned label set, from before
  system keywords were filtered out. The census reads `unseen: 0` across six messages from a
  sender never seen before, from before that reason was recorded per message. A migration has
  to cope with data shaped by old bugs, and a rebuilt fixture would leave them out: built from a
  correct model of the format, it could only show the current understanding, not what was
  actually written. Keep the file, not a script that makes one.
- `replayed-cursor.state`: schema 3, two counted messages, holding the truncated cursor that the
  server accepts and answers with a replay. The cursor is still the bad one because the halt
  correctly declined to advance it.

The run that produced these also left a third file, recording the state after a replay was
consumed: 48 messages counted from one truncation. It reproduces nothing, so it is not kept.
