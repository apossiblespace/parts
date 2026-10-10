-- When the User last chose about Product updates (ADR-0020). NULL means the
-- User never chose: the User does not get Product updates, and the app asks
-- with a banner. For a subscribed User this is the record of the choice:
-- consent when the User said yes in Parts, the soft opt-in when the User
-- kept the sign-up box ticked.
ALTER TABLE users ADD COLUMN product_updates_chosen_at TIMESTAMPTZ;
--;;
-- A User who already opted out made a choice, so the app does not ask again.
-- Every other existing account now has no choice, so it is not subscribed:
-- the privacy policy those Users accepted promised that marketing email
-- would be opt-in.
UPDATE users
SET product_updates_chosen_at = product_updates_opted_out_at
WHERE product_updates_opted_out_at IS NOT NULL;
