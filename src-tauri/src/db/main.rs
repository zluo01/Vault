use crate::db::queries;
use crate::model::database::{Folder, FolderData, Media, Setting, Tag};
use crate::model::parser::MediaItem;
use log::{debug, error};
use serde_json::{json, Value};
use sqlx::sqlite::{SqliteConnectOptions, SqliteJournalMode};
use sqlx::{
    migrate::MigrateDatabase, sqlite::SqlitePoolOptions, Pool, QueryBuilder, Row, Sqlite,
    SqlitePool,
};
use std::fs;
use std::result::Result;
use std::str::FromStr;
use std::time::Duration;
use tauri::{Manager, Runtime};

pub fn initialize<R: Runtime>(app: &tauri::AppHandle<R>) -> Result<(), String> {
    tauri::async_runtime::block_on(async move {
        let app_dir = app.path().app_data_dir().unwrap();
        debug!("App Directory: {:?}", app_dir);

        let create_dir_result = fs::create_dir_all(&app_dir);
        if let Err(e) = create_dir_result {
            let err_msg = format!("Fail to create App directory {:?}. Error: {:?}", app_dir, e);
            error!("{:?}", err_msg);
            return Err(err_msg);
        }

        let db_url = get_database_path(app);
        debug!("Database URL: {:?}", db_url);
        if !Sqlite::database_exists(&db_url).await.unwrap_or(false) {
            if let Err(e) = Sqlite::create_database(&db_url).await {
                let err_msg = format!("Fail to create db at {:?}. Error: {:?}", db_url, e);
                error!("{:?}", err_msg);
                return Err(err_msg);
            }
            let pool = SqlitePool::connect(&db_url)
                .await
                .map_err(|e| format!("Fail to connect to db at {:?}. Error: {:?}", db_url, e))?;
            if let Err(e) = create_tables(&pool).await {
                let err_msg = format!("Fail to create tables. Error: {:?}", e);
                error!("{:?}", err_msg);
                pool.close().await;
                return Err(err_msg);
            }
            pool.close().await;
        }
        Ok(())
    })
}

async fn create_tables(pool: &Pool<Sqlite>) -> Result<(), sqlx::Error> {
    sqlx::query(queries::CREATE_TABLE_QUERY)
        .execute(pool)
        .await?;
    Ok(())
}

pub fn get_database_path<R: Runtime>(app: &tauri::AppHandle<R>) -> String {
    let app_dir = app.path().app_data_dir().unwrap();
    format!("sqlite://{}/sqlite.db", app_dir.display())
}

pub async fn create_pool(db_path: &str) -> Result<Pool<Sqlite>, sqlx::Error> {
    let options = SqliteConnectOptions::from_str(db_path)?
        .foreign_keys(true)
        .journal_mode(SqliteJournalMode::Wal)
        .busy_timeout(Duration::from_secs(5));
    let pool = SqlitePoolOptions::new()
        .max_connections(10)
        .connect_with(options)
        .await?;
    Ok(pool)
}

pub async fn get_settings(pool: &Pool<Sqlite>) -> Result<Setting, sqlx::Error> {
    let settings = sqlx::query_as::<_, Setting>(queries::GET_SETTINGS)
        .fetch_one(pool)
        .await?;
    Ok(settings)
}

pub async fn get_skip_folders(pool: &Pool<Sqlite>) -> Result<Vec<String>, sqlx::Error> {
    let skip_folders = sqlx::query(queries::GET_SKIP_FOLDERS)
        .fetch_one(pool)
        .await?;
    Ok(skip_folders
        .get::<String, usize>(0)
        .split(",")
        .map(|v| v.trim().to_string())
        .collect())
}

pub async fn update_hide_side_panel(
    pool: &Pool<Sqlite>,
    hide_panel: &i32,
) -> Result<(), sqlx::Error> {
    let _ = sqlx::query(queries::UPDATE_HIDE_PANEL)
        .bind(hide_panel)
        .execute(pool)
        .await?;
    Ok(())
}

pub async fn update_skip_folders(
    pool: &Pool<Sqlite>,
    skip_folders: &str,
) -> Result<(), sqlx::Error> {
    let _ = sqlx::query(queries::UPDATE_SKIP_FOLDERS)
        .bind(skip_folders)
        .execute(pool)
        .await?;
    Ok(())
}

pub async fn insert_folder_data(
    pool: &Pool<Sqlite>,
    folder_name: &str,
    path: &str,
) -> Result<(), sqlx::Error> {
    let _ = sqlx::query(queries::INSERT_NEW_FOLDER_DATA)
        .bind(folder_name)
        .bind(path)
        .execute(pool)
        .await?;
    Ok(())
}

pub async fn get_folder_list(pool: &Pool<Sqlite>) -> Result<Vec<Folder>, sqlx::Error> {
    let folder_list = sqlx::query_as::<_, Folder>(queries::GET_FOLDER_LIST)
        .fetch_all(pool)
        .await?;
    Ok(folder_list)
}

pub async fn get_folder_data(
    pool: &Pool<Sqlite>,
    position: &i32,
) -> Result<FolderData, sqlx::Error> {
    let folder_data = sqlx::query_as::<_, FolderData>(queries::GET_FOLDER_DATA)
        .bind(position)
        .fetch_one(pool)
        .await?;
    Ok(folder_data)
}

pub async fn insert_new_media(
    pool: &Pool<Sqlite>,
    folder_name: &str,
    data: &[MediaItem],
) -> Result<(), sqlx::Error> {
    if data.is_empty() {
        return Ok(());
    }

    let mut tx = pool.begin().await?;
    sqlx::query(queries::CLEAR_MEDIA)
        .bind(folder_name)
        .execute(&mut *tx)
        .await?;
    sqlx::query(queries::CLEAR_TAGS)
        .bind(folder_name)
        .execute(&mut *tx)
        .await?;

    // Batch insert media items in chunks to stay within SQLite bind parameter limits
    for chunk in data.chunks(100) {
        let mut query_builder = QueryBuilder::<Sqlite>::new(
            "INSERT INTO media (type, path, title, posters, year, file, seasons, folder) ",
        );
        query_builder.push_values(chunk, |mut row, media| {
            row.push_bind(media.media_type())
                .push_bind(media.path())
                .push_bind(media.title())
                .push_bind(media.posters())
                .push_bind(media.year())
                .push_bind(media.file())
                .push_bind(media.seasons())
                .push_bind(folder_name);
        });
        query_builder.build().execute(&mut *tx).await?;
    }

    // Batch insert tags for each media item
    for media in data {
        insert_tags_batch(&mut tx, folder_name, media.path(), media.tags(), "tags").await?;
        insert_tags_batch(&mut tx, folder_name, media.path(), media.genres(), "genres").await?;
        insert_tags_batch(&mut tx, folder_name, media.path(), media.actors(), "actors").await?;
        insert_tags_batch(
            &mut tx,
            folder_name,
            media.path(),
            media.studios(),
            "studios",
        )
        .await?;
    }
    tx.commit().await?;
    Ok(())
}

async fn insert_tags_batch(
    tx: &mut sqlx::Transaction<'_, sqlx::Sqlite>,
    folder_name: &str,
    path: &str,
    tags: &[String],
    tag_type: &str,
) -> Result<(), sqlx::Error> {
    if tags.is_empty() {
        return Ok(());
    }

    for chunk in tags.chunks(100) {
        let mut query_builder =
            QueryBuilder::<Sqlite>::new("INSERT INTO tags (folder_name, path, name, t) ");
        query_builder.push_values(chunk, |mut row, tag| {
            row.push_bind(folder_name)
                .push_bind(path)
                .push_bind(tag)
                .push_bind(tag_type);
        });
        query_builder.push(" ON CONFLICT DO NOTHING");
        query_builder.build().execute(&mut **tx).await?;
    }
    Ok(())
}

pub async fn get_folder_media(
    pool: &Pool<Sqlite>,
    position: &i32,
    server_port: &u16,
    filter_type: u8,
    tags: &[Tag],
) -> Result<Vec<Media>, sqlx::Error> {
    let tags_json = serde_json::to_string(tags).unwrap_or_else(|_| "[]".to_string());

    let media_list = sqlx::query(queries::GET_FOLDER_CONTENT)
        .bind(&tags_json)
        .bind(position)
        .bind(filter_type)
        .fetch_all(pool)
        .await?
        .iter()
        .map(|r| Media::from_row(r, server_port))
        .collect::<Result<Vec<_>, _>>()?;
    Ok(media_list)
}

pub async fn get_folder_media_tags(
    pool: &Pool<Sqlite>,
    position: &i32,
) -> Result<Vec<Value>, sqlx::Error> {
    let rows = sqlx::query(queries::TAGS_IN_FOLDER)
        .bind(position)
        .fetch_all(pool)
        .await?;

    let result = rows
        .iter()
        .map(|r| {
            let label: String = r.get("label");
            let options_str: String = r.get("options");
            let options: Value = serde_json::from_str(&options_str).unwrap_or_else(|e| {
                error!("Failed to parse tag options for {}: {}", label, e);
                json!([])
            });
            json!({ "label": label, "options": options })
        })
        .collect();

    Ok(result)
}

pub async fn update_sort_type(
    pool: &Pool<Sqlite>,
    position: &i32,
    sort_type: &u8,
) -> Result<(), sqlx::Error> {
    let _ = sqlx::query(queries::UPDATE_SORT_TYPE)
        .bind(sort_type)
        .bind(position)
        .execute(pool)
        .await?;
    Ok(())
}

pub async fn update_folder_path(
    pool: &Pool<Sqlite>,
    position: &i32,
    path: &str,
) -> Result<(), sqlx::Error> {
    let _ = sqlx::query(queries::UPDATE_FOLDER_PATH)
        .bind(path)
        .bind(position)
        .execute(pool)
        .await?;
    Ok(())
}

pub async fn reorder_folder(pool: &Pool<Sqlite>, folder_name: &[&str]) -> Result<(), sqlx::Error> {
    let mut tx = pool.begin().await?;
    for (i, name) in folder_name.iter().enumerate() {
        sqlx::query(queries::UPDATE_FOLDER_POSITION)
            .bind(i as i32)
            .bind(name)
            .execute(&mut *tx)
            .await?;
    }
    tx.commit().await?;
    Ok(())
}

pub async fn delete_folder(
    pool: &Pool<Sqlite>,
    name: &str,
    position: &i32,
) -> Result<(), sqlx::Error> {
    let mut tx = pool.begin().await?;
    sqlx::query(queries::DELETE_FOLDER)
        .bind(name)
        .execute(&mut *tx)
        .await?;
    sqlx::query(queries::SHIFT_FOLDER_POSITIONS)
        .bind(position)
        .execute(&mut *tx)
        .await?;
    tx.commit().await?;
    Ok(())
}

pub async fn update_folder_status(
    pool: &Pool<Sqlite>,
    status: &u8,
    position: &i32,
) -> Result<(), sqlx::Error> {
    let _ = sqlx::query(queries::UPDATE_FOLDER_STATUS)
        .bind(status)
        .bind(position)
        .execute(pool)
        .await?;
    Ok(())
}

pub async fn update_folder_filter_type(
    pool: &Pool<Sqlite>,
    position: &i32,
) -> Result<(), sqlx::Error> {
    let _ = sqlx::query(queries::UPDATE_FOLDER_FILTER_TYPE)
        .bind(position)
        .execute(pool)
        .await?;
    Ok(())
}

pub async fn get_folder_position(
    pool: &Pool<Sqlite>,
    name: &str,
    path: &str,
) -> Result<i32, sqlx::Error> {
    let position = sqlx::query(queries::GET_FOLDER_POSITION)
        .bind(name)
        .bind(path)
        .fetch_one(pool)
        .await?;
    Ok(position.get(0))
}

pub async fn recover(pool: &Pool<Sqlite>) -> Result<(), sqlx::Error> {
    let _ = sqlx::query(queries::RECOVER).execute(pool).await?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn tag(group: &str, label: &str) -> Tag {
        serde_json::from_value(json!({"group": group, "label": label})).unwrap()
    }

    use crate::model::parser::{Media as MediaBuilder, MediaType};
    use std::ffi::OsString;

    async fn setup_pool() -> Pool<Sqlite> {
        let pool = create_pool("sqlite::memory:").await.unwrap();
        create_tables(&pool).await.unwrap();
        pool
    }

    fn movie(
        title: &str,
        year: &str,
        file: &str,
        genres: &[&str],
        actors: &[&str],
        studios: &[&str],
    ) -> MediaItem {
        let mut m = MediaBuilder::default();
        m.set_media_type(MediaType::Movie);
        m.set_relative_path(OsString::from(title));
        m.set_title(title.to_string());
        m.set_year(year.to_string());
        m.set_file(file.to_string());
        m.add_poster("poster.jpg".to_string());
        for g in genres {
            m.add_genre(g.to_string());
        }
        m.set_actors(actors.iter().map(|a| a.to_string()).collect());
        for s in studios {
            m.add_studio(s.to_string());
        }
        m.movie().unwrap()
    }

    async fn seed_data(pool: &Pool<Sqlite>) {
        insert_folder_data(pool, "Movie", "/movies").await.unwrap();

        let media = vec![
            movie(
                "John Wick",
                "2014",
                "John Wick.mkv",
                &["Action", "Thriller"],
                &["Keanu Reeves"],
                &["Summit Entertainment"],
            ),
            movie(
                "The Dark Knight",
                "2008",
                "The Dark Knight.mkv",
                &["Action", "Crime", "Drama"],
                &["Christian Bale"],
                &["Warner Bros"],
            ),
            movie(
                "Blade Runner",
                "1982",
                "Blade Runner.mkv",
                &["Thriller", "Sci-Fi"],
                &["Harrison Ford"],
                &[],
            ),
            movie(
                "Dune",
                "2021",
                "Dune.mkv",
                &["Action", "Sci-Fi", "Drama"],
                &[],
                &["Warner Bros"],
            ),
            movie(
                "Love Letter",
                "1995",
                "Love Letter.mp4",
                &["Drama", "Romance"],
                &[],
                &[],
            ),
        ];

        insert_new_media(pool, "Movie", &media).await.unwrap();
    }

    // -- get_folder_media_tags --

    #[tokio::test]
    async fn media_tags_returns_grouped_tags() {
        let pool = setup_pool().await;
        seed_data(&pool).await;

        let result = get_folder_media_tags(&pool, &0).await.unwrap();

        // Should have groups: actors, genres, studios (alphabetical from SQL ORDER BY)
        let labels: Vec<&str> = result
            .iter()
            .map(|v| v["label"].as_str().unwrap())
            .collect();
        assert_eq!(labels, vec!["actors", "genres", "studios"]);

        // Genres should contain all distinct genre tags, sorted
        let genres: Vec<&str> = result.iter().find(|v| v["label"] == "genres").unwrap()["options"]
            .as_array()
            .unwrap()
            .iter()
            .map(|v| v["label"].as_str().unwrap())
            .collect();
        assert_eq!(
            genres,
            vec!["Action", "Crime", "Drama", "Romance", "Sci-Fi", "Thriller"]
        );
    }

    #[tokio::test]
    async fn media_tags_empty_folder_returns_empty() {
        let pool = setup_pool().await;
        // Insert folder with no media
        sqlx::query(
            "INSERT INTO folders (folder_name, position, path) VALUES ('Empty', 1, '/empty')",
        )
        .execute(&pool)
        .await
        .unwrap();

        let result = get_folder_media_tags(&pool, &1).await.unwrap();
        assert!(result.is_empty());
    }

    // -- get_folder_media (tag filtering) --

    #[tokio::test]
    async fn folder_media_no_tags_returns_all() {
        let pool = setup_pool().await;
        seed_data(&pool).await;

        let result = get_folder_media(&pool, &0, &8080, 0, &[]).await.unwrap();
        assert_eq!(result.len(), 5);
    }

    #[tokio::test]
    async fn folder_media_or_filter_single_tag() {
        let pool = setup_pool().await;
        seed_data(&pool).await;

        // OR filter with Action genre -> John Wick, The Dark Knight, Dune
        let tags = vec![tag("genres", "Action")];
        let result = get_folder_media(&pool, &0, &8080, 0, &tags).await.unwrap();
        let titles: Vec<&str> = result.iter().map(|m| m.title()).collect();
        assert_eq!(titles.len(), 3);
        assert!(titles.contains(&"John Wick"));
        assert!(titles.contains(&"The Dark Knight"));
        assert!(titles.contains(&"Dune"));
    }

    #[tokio::test]
    async fn folder_media_or_filter_multiple_tags_same_group() {
        let pool = setup_pool().await;
        seed_data(&pool).await;

        // OR filter: Action OR Thriller -> any media with at least one
        // John Wick (Action+Thriller), Dark Knight (Action), Blade Runner (Thriller), Dune (Action)
        let tags = vec![tag("genres", "Action"), tag("genres", "Thriller")];
        let result = get_folder_media(&pool, &0, &8080, 0, &tags).await.unwrap();
        let titles: Vec<&str> = result.iter().map(|m| m.title()).collect();
        assert_eq!(titles.len(), 4);
        assert!(titles.contains(&"John Wick"));
        assert!(titles.contains(&"The Dark Knight"));
        assert!(titles.contains(&"Blade Runner"));
        assert!(titles.contains(&"Dune"));
        // Love Letter has no Action or Thriller
        assert!(!titles.contains(&"Love Letter"));
    }

    #[tokio::test]
    async fn folder_media_and_filter_multiple_tags_same_group() {
        let pool = setup_pool().await;
        seed_data(&pool).await;

        // AND filter: Action AND Thriller -> must have both
        // Only John Wick has both Action + Thriller
        let tags = vec![tag("genres", "Action"), tag("genres", "Thriller")];
        let result = get_folder_media(&pool, &0, &8080, 1, &tags).await.unwrap();
        let titles: Vec<&str> = result.iter().map(|m| m.title()).collect();
        assert_eq!(titles, vec!["John Wick"]);
    }

    #[tokio::test]
    async fn folder_media_or_filter_across_groups() {
        let pool = setup_pool().await;
        seed_data(&pool).await;

        // OR filter: genres=Romance + studios=Warner Bros
        // Must match at least one from each group that has filter tags
        // Romance: Love Letter
        // Warner Bros: The Dark Knight, Dune
        // No overlap -> empty (each group must pass independently)
        let tags = vec![tag("genres", "Romance"), tag("studios", "Warner Bros")];
        let result = get_folder_media(&pool, &0, &8080, 0, &tags).await.unwrap();
        let titles: Vec<&str> = result.iter().map(|m| m.title()).collect();
        // Must satisfy both groups: Romance genre AND Warner Bros studio
        // No movie has both -> empty
        assert!(titles.is_empty());
    }

    #[tokio::test]
    async fn folder_media_or_filter_across_groups_with_matches() {
        let pool = setup_pool().await;
        seed_data(&pool).await;

        // OR filter: genres=Drama + studios=Warner Bros
        // Drama: Dark Knight, Dune, Love Letter
        // Warner Bros: Dark Knight, Dune
        // Intersection (must pass both groups): Dark Knight, Dune
        let tags = vec![tag("genres", "Drama"), tag("studios", "Warner Bros")];
        let result = get_folder_media(&pool, &0, &8080, 0, &tags).await.unwrap();
        let titles: Vec<&str> = result.iter().map(|m| m.title()).collect();
        assert_eq!(titles.len(), 2);
        assert!(titles.contains(&"The Dark Knight"));
        assert!(titles.contains(&"Dune"));
    }

    #[tokio::test]
    async fn folder_media_no_match_returns_empty() {
        let pool = setup_pool().await;
        seed_data(&pool).await;

        let tags = vec![tag("genres", "Horror")];
        let result = get_folder_media(&pool, &0, &8080, 0, &tags).await.unwrap();
        assert!(result.is_empty());
    }

    // -- settings --

    #[tokio::test]
    async fn get_settings_returns_defaults() {
        let pool = setup_pool().await;

        let settings = get_settings(&pool).await.unwrap();
        let json = serde_json::to_value(&settings).unwrap();
        // hide_panel=0 serializes as showSidePanel=true
        assert_eq!(json["showSidePanel"], true);
        assert_eq!(json["skipFolders"], json!([]));
    }

    #[tokio::test]
    async fn update_and_get_skip_folders() {
        let pool = setup_pool().await;

        update_skip_folders(&pool, "_Todo,_Bonus").await.unwrap();
        let result = get_skip_folders(&pool).await.unwrap();
        assert_eq!(result, vec!["_Todo", "_Bonus"]);
    }

    #[tokio::test]
    async fn update_hide_side_panel_toggles() {
        let pool = setup_pool().await;

        update_hide_side_panel(&pool, &1).await.unwrap();
        let settings = get_settings(&pool).await.unwrap();
        let json = serde_json::to_value(&settings).unwrap();
        // hide_panel=1 -> showSidePanel=false
        assert_eq!(json["showSidePanel"], false);
    }

    // -- folder operations --

    #[tokio::test]
    async fn insert_and_get_folder_list() {
        let pool = setup_pool().await;

        insert_folder_data(&pool, "Movie", "/movies").await.unwrap();
        insert_folder_data(&pool, "TV", "/tv").await.unwrap();

        let folders = get_folder_list(&pool).await.unwrap();
        assert_eq!(folders.len(), 2);
        assert_eq!(folders[0].folder_name(), "Movie");
        assert_eq!(folders[1].folder_name(), "TV");
    }

    #[tokio::test]
    async fn insert_folder_auto_increments_position() {
        let pool = setup_pool().await;

        insert_folder_data(&pool, "A", "/a").await.unwrap();
        insert_folder_data(&pool, "B", "/b").await.unwrap();
        insert_folder_data(&pool, "C", "/c").await.unwrap();

        let folders = get_folder_list(&pool).await.unwrap();
        let positions: Vec<i64> = folders
            .iter()
            .map(|f| {
                serde_json::to_value(f).unwrap()["position"]
                    .as_i64()
                    .unwrap()
            })
            .collect();
        assert_eq!(positions, vec![0, 1, 2]);
    }

    #[tokio::test]
    async fn get_folder_data_returns_defaults() {
        let pool = setup_pool().await;
        seed_data(&pool).await;

        let data = get_folder_data(&pool, &0).await.unwrap();
        let json = serde_json::to_value(&data).unwrap();
        assert_eq!(json["name"], "Movie");
        assert_eq!(json["sort"], 0);
        assert_eq!(json["filterType"], 0);
    }

    #[tokio::test]
    async fn update_sort_type_changes_value() {
        let pool = setup_pool().await;
        seed_data(&pool).await;

        update_sort_type(&pool, &0, &1).await.unwrap();
        let data = get_folder_data(&pool, &0).await.unwrap();
        let json = serde_json::to_value(&data).unwrap();
        assert_eq!(json["sort"], 1);
    }

    #[tokio::test]
    async fn update_folder_path_changes_path() {
        let pool = setup_pool().await;
        seed_data(&pool).await;

        update_folder_path(&pool, &0, "/new/path").await.unwrap();
        let data = get_folder_data(&pool, &0).await.unwrap();
        let json = serde_json::to_value(&data).unwrap();
        assert_eq!(json["path"], "/new/path");
    }

    #[tokio::test]
    async fn update_folder_filter_type_toggles() {
        let pool = setup_pool().await;
        seed_data(&pool).await;

        // Default is 0, toggle to 1
        update_folder_filter_type(&pool, &0).await.unwrap();
        let data = get_folder_data(&pool, &0).await.unwrap();
        let json = serde_json::to_value(&data).unwrap();
        assert_eq!(json["filterType"], 1);

        // Toggle back to 0
        update_folder_filter_type(&pool, &0).await.unwrap();
        let data = get_folder_data(&pool, &0).await.unwrap();
        let json = serde_json::to_value(&data).unwrap();
        assert_eq!(json["filterType"], 0);
    }

    #[tokio::test]
    async fn reorder_folders() {
        let pool = setup_pool().await;

        insert_folder_data(&pool, "A", "/a").await.unwrap();
        insert_folder_data(&pool, "B", "/b").await.unwrap();
        insert_folder_data(&pool, "C", "/c").await.unwrap();

        // Reverse order: C=0, B=1, A=2
        reorder_folder(&pool, &["C", "B", "A"]).await.unwrap();

        let folders = get_folder_list(&pool).await.unwrap();
        let names: Vec<&str> = folders.iter().map(|f| f.folder_name()).collect();
        assert_eq!(names, vec!["C", "B", "A"]);
    }

    #[tokio::test]
    async fn delete_folder_shifts_positions() {
        let pool = setup_pool().await;

        insert_folder_data(&pool, "A", "/a").await.unwrap();
        insert_folder_data(&pool, "B", "/b").await.unwrap();
        insert_folder_data(&pool, "C", "/c").await.unwrap();

        // Delete B at position 1
        delete_folder(&pool, "B", &1).await.unwrap();

        let folders = get_folder_list(&pool).await.unwrap();
        let names: Vec<&str> = folders.iter().map(|f| f.folder_name()).collect();
        assert_eq!(names, vec!["A", "C"]);

        // C should have shifted from position 2 to 1
        let positions: Vec<i64> = folders
            .iter()
            .map(|f| {
                serde_json::to_value(f).unwrap()["position"]
                    .as_i64()
                    .unwrap()
            })
            .collect();
        assert_eq!(positions, vec![0, 1]);
    }

    #[tokio::test]
    async fn delete_folder_cascades_media_and_tags() {
        let pool = setup_pool().await;
        seed_data(&pool).await;

        delete_folder(&pool, "Movie", &0).await.unwrap();

        let folders = get_folder_list(&pool).await.unwrap();
        assert!(folders.is_empty());

        // Media and tags should be cascade-deleted
        let media_count: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM media")
            .fetch_one(&pool)
            .await
            .unwrap();
        assert_eq!(media_count, 0);

        let tag_count: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM tags")
            .fetch_one(&pool)
            .await
            .unwrap();
        assert_eq!(tag_count, 0);
    }

    #[tokio::test]
    async fn get_folder_position_returns_correct_position() {
        let pool = setup_pool().await;

        insert_folder_data(&pool, "Movie", "/movies").await.unwrap();
        insert_folder_data(&pool, "TV", "/tv").await.unwrap();

        let pos = get_folder_position(&pool, "TV", "/tv").await.unwrap();
        assert_eq!(pos, 1);
    }

    #[tokio::test]
    async fn update_folder_status_and_recover() {
        let pool = setup_pool().await;
        seed_data(&pool).await;

        // Set status to 1 (loading)
        update_folder_status(&pool, &1, &0).await.unwrap();
        let data = get_folder_data(&pool, &0).await.unwrap();
        let json = serde_json::to_value(&data).unwrap();
        assert_eq!(json["status"], 1);

        // Recover should set status=1 folders to status=2
        recover(&pool).await.unwrap();
        let data = get_folder_data(&pool, &0).await.unwrap();
        let json = serde_json::to_value(&data).unwrap();
        assert_eq!(json["status"], 2);
    }

    // -- folder media sorting --

    #[tokio::test]
    async fn folder_media_default_sort_by_path() {
        let pool = setup_pool().await;
        seed_data(&pool).await;

        let result = get_folder_media(&pool, &0, &8080, 0, &[]).await.unwrap();
        let titles: Vec<&str> = result.iter().map(|m| m.title()).collect();
        // Default sort (sort_type=0) is by path ascending
        assert_eq!(
            titles,
            vec![
                "Blade Runner",
                "Dune",
                "John Wick",
                "Love Letter",
                "The Dark Knight"
            ]
        );
    }

    #[tokio::test]
    async fn folder_media_sort_by_title_asc() {
        let pool = setup_pool().await;
        seed_data(&pool).await;

        update_sort_type(&pool, &0, &1).await.unwrap();
        let result = get_folder_media(&pool, &0, &8080, 0, &[]).await.unwrap();
        let titles: Vec<&str> = result.iter().map(|m| m.title()).collect();
        assert_eq!(
            titles,
            vec![
                "Blade Runner",
                "Dune",
                "John Wick",
                "Love Letter",
                "The Dark Knight"
            ]
        );
    }

    #[tokio::test]
    async fn folder_media_sort_by_title_desc() {
        let pool = setup_pool().await;
        seed_data(&pool).await;

        update_sort_type(&pool, &0, &2).await.unwrap();
        let result = get_folder_media(&pool, &0, &8080, 0, &[]).await.unwrap();
        let titles: Vec<&str> = result.iter().map(|m| m.title()).collect();
        assert_eq!(
            titles,
            vec![
                "The Dark Knight",
                "Love Letter",
                "John Wick",
                "Dune",
                "Blade Runner"
            ]
        );
    }

    #[tokio::test]
    async fn folder_media_sort_by_year_asc() {
        let pool = setup_pool().await;
        seed_data(&pool).await;

        update_sort_type(&pool, &0, &3).await.unwrap();
        let result = get_folder_media(&pool, &0, &8080, 0, &[]).await.unwrap();
        let titles: Vec<&str> = result.iter().map(|m| m.title()).collect();
        // 1982, 1995, 2008, 2014, 2021
        assert_eq!(
            titles,
            vec![
                "Blade Runner",
                "Love Letter",
                "The Dark Knight",
                "John Wick",
                "Dune"
            ]
        );
    }

    #[tokio::test]
    async fn folder_media_sort_by_year_desc() {
        let pool = setup_pool().await;
        seed_data(&pool).await;

        update_sort_type(&pool, &0, &4).await.unwrap();
        let result = get_folder_media(&pool, &0, &8080, 0, &[]).await.unwrap();
        let titles: Vec<&str> = result.iter().map(|m| m.title()).collect();
        // 2021, 2014, 2008, 1995, 1982
        assert_eq!(
            titles,
            vec![
                "Dune",
                "John Wick",
                "The Dark Knight",
                "Love Letter",
                "Blade Runner"
            ]
        );
    }

    #[tokio::test]
    async fn folder_media_poster_urls_contain_folder_name() {
        let pool = setup_pool().await;
        seed_data(&pool).await;

        let result = get_folder_media(&pool, &0, &8080, 0, &[]).await.unwrap();
        let first = &result[0];
        let json = serde_json::to_value(first).unwrap();
        let poster_url = json["posters"]["main"].as_str().unwrap();
        assert!(
            poster_url.contains("/Movie/"),
            "poster URL should contain folder name, got: {}",
            poster_url
        );
    }
}
