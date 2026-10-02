-- SCN-B brownfield change (FR-URL-008/009), written after the HUMAN design approval of run
-- b4ff60fc-d24e-4833-8fd7-54625c1ccd48. Additive: NULL means the link never expires, so every existing
-- link keeps its behavior.
ALTER TABLE link ADD COLUMN expires_at TIMESTAMP WITH TIME ZONE NULL;
