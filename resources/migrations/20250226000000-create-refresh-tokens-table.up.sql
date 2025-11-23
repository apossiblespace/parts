CREATE TABLE refresh_tokens (
  id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  user_id UUID NOT NULL,
  token_id TEXT NOT NULL UNIQUE,
  expires_at BIGINT NOT NULL,
  created_at BIGINT NOT NULL,
  FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);
--;;

CREATE INDEX idx_refresh_tokens_user_id ON refresh_tokens(user_id);
--;;
CREATE INDEX idx_refresh_tokens_token_id ON refresh_tokens(token_id);
