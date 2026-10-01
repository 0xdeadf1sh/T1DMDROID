//! The table-backed suites pass silently without tables; this fails unless a skip is explicit.

#[test]
fn tables_dir_is_set_or_the_skip_is_explicit() {
    if matches!(std::env::var("LIBRE3_TABLES_SKIP").as_deref(), Ok("1")) {
        eprintln!("table-backed vectors skipped: LIBRE3_TABLES_SKIP=1");
        return;
    }
    let dir = std::env::var("LIBRE3_TABLES_DIR").expect(
        "LIBRE3_TABLES_DIR unset: the table-backed vectors would skip; \
         set it to the tables dir, or LIBRE3_TABLES_SKIP=1",
    );
    assert!(
        std::path::Path::new(&dir).is_dir(),
        "LIBRE3_TABLES_DIR={dir} is not a directory"
    );
}
