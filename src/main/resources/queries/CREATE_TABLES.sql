CREATE TABLE IF NOT EXISTS settings
(
    id           INTEGER PRIMARY KEY CHECK (id = 0),
    hide_panel   INTEGER NOT NULL DEFAULT 0 CHECK (hide_panel IN (0, 1)),
    skip_folders TEXT    NOT NULL DEFAULT '',
    theme        TEXT    NOT NULL DEFAULT 'SYSTEM' CHECK (theme IN ('SYSTEM', 'LIGHT', 'DARK'))
) STRICT;
INSERT OR IGNORE INTO settings (id)
VALUES (0);

CREATE TABLE IF NOT EXISTS folders
(
    name        TEXT    NOT NULL PRIMARY KEY,
    position    INTEGER NOT NULL,
    path        TEXT    NOT NULL UNIQUE,
    sort_type   TEXT    NOT NULL DEFAULT 'DEFAULT' CHECK (sort_type IN
                                                          ('DEFAULT', 'TITLE_ASC', 'TITLE_DSC', 'YEAR_ASC',
                                                           'YEAR_DSC')),
    filter_type TEXT    NOT NULL DEFAULT 'OR' CHECK (filter_type IN ('OR', 'AND')),
    status      TEXT    NOT NULL DEFAULT 'NONE' CHECK (status IN ('NONE', 'LOADING', 'ERROR'))
) STRICT;
CREATE INDEX IF NOT EXISTS folders_position_index
    ON folders (position);
CREATE TRIGGER IF NOT EXISTS folders_close_position_gap
    AFTER DELETE
    ON folders
BEGIN
    UPDATE folders SET position = position - 1 WHERE position > OLD.position;
END;

CREATE TABLE IF NOT EXISTS media
(
    type    TEXT NOT NULL CHECK (type IN ('MOVIE', 'TV_SHOW', 'COMIC')),
    path    TEXT NOT NULL PRIMARY KEY,
    title   TEXT NOT NULL,
    posters TEXT NOT NULL,
    year    TEXT,
    runtime TEXT,
    file    TEXT,
    folder  TEXT NOT NULL
        REFERENCES folders (name)
            ON UPDATE CASCADE ON DELETE CASCADE,
    CHECK ( (type = 'TV_SHOW' AND file IS NULL)
        OR (type IN ('MOVIE', 'COMIC') AND file IS NOT NULL) )
) STRICT;
CREATE INDEX IF NOT EXISTS media_title_index
    ON media (title);
CREATE INDEX IF NOT EXISTS media_year_index
    ON media (year);
CREATE INDEX IF NOT EXISTS media_folder_index ON media (folder);

CREATE TABLE IF NOT EXISTS episodes
(
    show_path TEXT NOT NULL
        REFERENCES media (path)
            ON UPDATE CASCADE ON DELETE CASCADE,
    path      TEXT NOT NULL,
    title     TEXT NOT NULL,
    file      TEXT NOT NULL,
    season    TEXT NOT NULL,
    episode   TEXT NOT NULL,
    runtime   TEXT NOT NULL,
    preview   TEXT,
    PRIMARY KEY (show_path, season, episode)
) STRICT;

CREATE TABLE IF NOT EXISTS tags
(
    path     TEXT NOT NULL
        REFERENCES media (path)
            ON UPDATE CASCADE ON DELETE CASCADE,
    category TEXT NOT NULL CHECK (category IN ('GENRE', 'STUDIO', 'ACTOR', 'TAG')),
    name     TEXT NOT NULL,
    UNIQUE (path, name, category)
) STRICT;
