use ciborium::Value;
use p256::ecdsa::VerifyingKey;

use super::webauthn::Refusal;

/// COSE algorithm identifiers a credential may use: ES256 only.
pub const ALGORITHMS: [i64; 1] = [-7];

const EC2: i64 = 2;
const P256: i64 = 1;

/// Reads one COSE_Key from the front of `input`, leaving what follows it.
pub fn read_key(input: &mut &[u8]) -> Result<VerifyingKey, Refusal> {
    let Value::Map(entries) = ciborium::from_reader(&mut *input).map_err(|_| Refusal::Malformed)?
    else {
        return Err(Refusal::Malformed);
    };
    let find = |label: i64| {
        entries
            .iter()
            .find(|(key, _)| key.as_integer().and_then(|n| i64::try_from(n).ok()) == Some(label))
            .map(|(_, value)| value)
    };
    let number = |label| find(label).and_then(Value::as_integer);
    let coordinate = |label| {
        find(label)
            .and_then(Value::as_bytes)
            .filter(|c| c.len() == 32)
    };

    if number(1) != Some(EC2.into()) || number(-1) != Some(P256.into()) {
        return Err(Refusal::Algorithm);
    }
    let algorithm = number(3).and_then(|n| i64::try_from(n).ok());
    if !algorithm.is_some_and(|algorithm| ALGORITHMS.contains(&algorithm)) {
        return Err(Refusal::Algorithm);
    }
    let (Some(x), Some(y)) = (coordinate(-2), coordinate(-3)) else {
        return Err(Refusal::Malformed);
    };
    let point = [&[4][..], x, y].concat();
    VerifyingKey::from_sec1_bytes(&point).map_err(|_| Refusal::Malformed)
}
