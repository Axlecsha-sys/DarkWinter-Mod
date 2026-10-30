//! Read-only projection of IdentityStore v1. Never opens a store for writing,
//! regenerates keys, imports a profile, or returns private material.
use anyhow::{Context, Result, ensure};
use base64::{Engine, engine::general_purpose::URL_SAFE_NO_PAD};
use ed25519_dalek::{
    VerifyingKey,
    pkcs8::{DecodePublicKey, EncodePublicKey},
};
use launcher_common::require_plain_path;
use serde::{Deserialize, Serialize, de::IgnoredAny};
use sha2::{Digest, Sha256};
use std::{fs::File, io::Read, path::Path};
use zeroize::Zeroizing;

const PROFILE_LIMIT: u64 = 8 * 1024 * 1024;

#[derive(Clone, Debug, Serialize)]
pub struct ProfileSnapshot {
    pub id: String,
    pub nickname: String,
    pub model: Model,
    pub revision: i64,
    pub created_at: i64,
}

#[derive(Clone, Copy, Debug, Deserialize, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum Model {
    Male,
    Female,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Bundle {
    schema: u32,
    profile: PublicProfile,
    #[serde(rename = "privateKey")]
    _private_key: IgnoredAny,
    #[serde(rename = "encryptionPrivateKey")]
    _encryption_private_key: IgnoredAny,
    #[serde(rename = "sections")]
    _sections: Sections,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Sections {
    #[serde(rename = "FRIENDS")]
    _friends: Option<IgnoredAny>,
    #[serde(rename = "INVITATIONS")]
    _invitations: Option<IgnoredAny>,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
struct PublicProfile {
    schema: u32,
    id: String,
    public_key: String,
    encryption_public_key: String,
    nickname: String,
    model: Model,
    privacy: Privacy,
    revision: i64,
    created_at: i64,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Privacy {
    presence: Visibility,
    requests: ContactPolicy,
    messages: ContactPolicy,
    invitations: ContactPolicy,
}

#[derive(Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum Visibility {
    Nobody,
    Friends,
    Public,
}
#[derive(Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum ContactPolicy {
    Nobody,
    Friends,
    Anyone,
}

pub fn read_snapshot(game_dir: &Path) -> Result<Option<ProfileSnapshot>> {
    let path = game_dir.join("darkwinter-online/profile/profile.json");
    require_plain_path(&path)?;
    if !path.try_exists()? {
        return Ok(None);
    }
    let file = File::open(path).context("Профиль недоступен для чтения; данные сохранены")?;
    ensure!(
        file.metadata()?.is_file(),
        "Профиль должен быть обычным файлом"
    );
    let mut bytes = Zeroizing::new(Vec::new());
    file.take(PROFILE_LIMIT + 1).read_to_end(&mut bytes)?;
    ensure!(
        bytes.len() as u64 <= PROFILE_LIMIT,
        "Профиль превышает 8 МиБ; данные сохранены"
    );
    // IgnoredAny skips secret values without constructing Strings for them.
    let bundle: Bundle = serde_json::from_slice(&bytes).map_err(|_| {
        anyhow::anyhow!("Не удалось прочитать формат профиля; исходный файл сохранён")
    })?;
    ensure!(
        bundle.schema == 1 && bundle.profile.schema == 1,
        "Неизвестная версия профиля; данные сохранены"
    );
    let public = bundle.profile;
    ensure!(
        public.revision >= 0 && public.created_at >= 0,
        "Некорректная ревизия профиля"
    );
    validate_nickname(&public.nickname)?;
    let encoded = URL_SAFE_NO_PAD
        .decode(&public.public_key)
        .map_err(|_| anyhow::anyhow!("Некорректный открытый ключ профиля"))?;
    ensure!(
        URL_SAFE_NO_PAD.encode(&encoded) == public.public_key,
        "Неканонический открытый ключ"
    );
    let key = VerifyingKey::from_public_key_der(&encoded)
        .map_err(|_| anyhow::anyhow!("Некорректный открытый ключ Ed25519"))?;
    let canonical = key
        .to_public_key_der()
        .map_err(|_| anyhow::anyhow!("Некорректный открытый ключ Ed25519"))?;
    ensure!(
        canonical.as_bytes() == encoded,
        "Неканонический SPKI профиля"
    );
    let expected_id = format!("dw1_{}", URL_SAFE_NO_PAD.encode(Sha256::digest(&encoded)));
    ensure!(
        public.id == expected_id,
        "DarkWinter ID не соответствует открытому ключу; профиль сохранён"
    );
    let encryption = URL_SAFE_NO_PAD
        .decode(&public.encryption_public_key)
        .map_err(|_| anyhow::anyhow!("Некорректный открытый ключ X25519"))?;
    const X25519_SPKI_PREFIX: &[u8] = &[
        0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x6e, 0x03, 0x21, 0x00,
    ];
    ensure!(
        encryption.len() == 44
            && encryption.starts_with(X25519_SPKI_PREFIX)
            && URL_SAFE_NO_PAD.encode(&encryption) == public.encryption_public_key,
        "Некорректный SPKI X25519"
    );
    // These fields were strictly decoded, but never imply online authorization or presence.
    let Privacy {
        presence: _presence,
        requests: _requests,
        messages: _messages,
        invitations: _invitations,
    } = public.privacy;
    Ok(Some(ProfileSnapshot {
        id: public.id,
        nickname: public.nickname,
        model: public.model,
        revision: public.revision,
        created_at: public.created_at,
    }))
}

fn validate_nickname(name: &str) -> Result<()> {
    // Java String.strip / Character.isWhitespace deliberately exclude NBSP, U+2007 and U+202F.
    fn java_whitespace(c: char) -> bool {
        matches!(c, '\u{0009}'..='\u{000d}' | '\u{001c}'..='\u{0020}' | '\u{1680}' | '\u{2000}'..='\u{2006}'
            | '\u{2008}'..='\u{200a}' | '\u{2028}' | '\u{2029}' | '\u{205f}' | '\u{3000}')
    }
    ensure!(
        !name.is_empty()
            && name.chars().count() <= 32
            && name.trim_matches(java_whitespace) == name,
        "Некорректное имя профиля"
    );
    ensure!(
        !name.chars().any(|c| c.is_control() || c == '§'),
        "Недопустимые символы в имени профиля"
    );
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use ed25519_dalek::SigningKey;
    use serde_json::json;

    fn fixture() -> serde_json::Value {
        let key = SigningKey::from_bytes(&[7; 32])
            .verifying_key()
            .to_public_key_der()
            .unwrap();
        let mut encryption = vec![
            0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x6e, 0x03, 0x21, 0x00,
        ];
        encryption.extend_from_slice(&[9; 32]);
        json!({"schema":1,"profile":{
            "schema":1,"id":format!("dw1_{}", URL_SAFE_NO_PAD.encode(Sha256::digest(key.as_bytes()))),
            "publicKey":URL_SAFE_NO_PAD.encode(key.as_bytes()),"encryptionPublicKey":URL_SAFE_NO_PAD.encode(encryption),
            "nickname":"Путник ❄","model":"FEMALE","privacy":{"presence":"FRIENDS","requests":"ANYONE","messages":"FRIENDS","invitations":"FRIENDS"},
            "revision":3,"createdAt":1},
            "privateKey":"SECRET-NEVER-EXPORT","encryptionPrivateKey":"SECRET-X25519","sections":{}})
    }

    fn write_fixture(dir: &Path, value: &serde_json::Value) -> std::path::PathBuf {
        let path = dir.join("darkwinter-online/profile/profile.json");
        std::fs::create_dir_all(path.parent().unwrap()).unwrap();
        std::fs::write(&path, serde_json::to_vec(value).unwrap()).unwrap();
        path
    }

    #[test]
    fn projects_public_fields_without_exporting_secrets_or_mutating_source() {
        let dir = tempfile::tempdir().unwrap();
        let path = write_fixture(dir.path(), &fixture());
        let before = std::fs::read(&path).unwrap();
        let profile = read_snapshot(dir.path()).unwrap().unwrap();
        let report = serde_json::to_string(&profile).unwrap();
        assert_eq!(profile.nickname, "Путник ❄");
        assert!(!report.contains("SECRET"));
        assert!(!format!("{profile:?}").contains("SECRET"));
        assert_eq!(std::fs::read(path).unwrap(), before);
    }

    #[test]
    fn preserves_invalid_future_and_duplicate_profiles() {
        let dir = tempfile::tempdir().unwrap();
        for (field, value) in [
            ("schema", json!(99)),
            ("id", json!("dw1_forged")),
            ("publicKey", json!("not-a-key")),
        ] {
            let mut doc = fixture();
            doc["profile"][field] = value;
            let path = write_fixture(dir.path(), &doc);
            let before = std::fs::read(&path).unwrap();
            assert!(read_snapshot(dir.path()).is_err());
            assert_eq!(std::fs::read(path).unwrap(), before);
        }
        let path = write_fixture(dir.path(), &fixture());
        let raw = std::fs::read_to_string(&path).unwrap().replacen(
            "\"schema\":1",
            "\"schema\":1,\"schema\":1",
            1,
        );
        std::fs::write(&path, raw).unwrap();
        assert!(read_snapshot(dir.path()).is_err());
    }

    #[test]
    fn missing_profile_is_not_created_and_errors_never_echo_secrets() {
        let dir = tempfile::tempdir().unwrap();
        assert!(read_snapshot(dir.path()).unwrap().is_none());
        assert!(!dir.path().join("darkwinter-online").exists());
        let mut doc = fixture();
        doc["profile"]["model"] = json!("SECRET-PRIVATE-MATERIAL");
        write_fixture(dir.path(), &doc);
        assert!(!format!("{:#}", read_snapshot(dir.path()).unwrap_err()).contains("SECRET"));
    }

    #[test]
    fn nickname_matches_java_unicode_rules() {
        validate_nickname("\u{00a0}Путник\u{00a0}").unwrap();
        validate_nickname(&"❄".repeat(32)).unwrap();
        for name in ["", " Player", "Player\n", "§red", "\u{2000}Player"] {
            assert!(validate_nickname(name).is_err());
        }
    }
}
