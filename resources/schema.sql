CREATE TABLE IF NOT EXISTS devices (
  id TEXT PRIMARY KEY,
  caption TEXT,
  type TEXT,
  updated_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS subscription_changes (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  url TEXT NOT NULL,
  action TEXT NOT NULL CHECK (action IN ('add', 'remove')),
  device TEXT,
  ts INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS subscription_changes_ts ON subscription_changes (ts);
CREATE INDEX IF NOT EXISTS subscription_changes_url ON subscription_changes (url);

CREATE TABLE IF NOT EXISTS episode_actions (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  podcast TEXT,
  episode TEXT,
  guid TEXT,
  action TEXT,
  timestamp TEXT,
  started INTEGER,
  position INTEGER,
  total INTEGER,
  device TEXT,
  received_ts INTEGER NOT NULL,
  raw TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS episode_actions_received ON episode_actions (received_ts);
CREATE INDEX IF NOT EXISTS episode_actions_episode ON episode_actions (episode);

CREATE TABLE IF NOT EXISTS feeds (
  url TEXT PRIMARY KEY,
  title TEXT,
  itunes_type TEXT,
  is_news_hint INTEGER,
  etag TEXT,
  last_modified TEXT,
  fetched_at INTEGER,
  error TEXT
);

CREATE TABLE IF NOT EXISTS episodes (
  feed_url TEXT NOT NULL,
  url TEXT NOT NULL,
  guid TEXT,
  title TEXT,
  pub_ts INTEGER,
  duration_s INTEGER,
  season INTEGER,
  episode_no INTEGER,
  PRIMARY KEY (feed_url, url)
);
CREATE INDEX IF NOT EXISTS episodes_url ON episodes (url);

CREATE TABLE IF NOT EXISTS skipped (
  episode_url TEXT PRIMARY KEY,
  feed_key TEXT,
  ts INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS picks (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  feed_key TEXT,
  episode_url TEXT NOT NULL,
  player TEXT,
  ts INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS starred (
  episode_url TEXT PRIMARY KEY,
  feed_key TEXT,
  note TEXT,
  ts INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS oneoffs (
  episode_url TEXT PRIMARY KEY,
  title TEXT,
  feed_url TEXT,
  duration_s INTEGER,
  source_link TEXT,
  added_ts INTEGER NOT NULL
);
