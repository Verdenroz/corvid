use std::future;
use std::sync::Arc;

use corvid_milter::daemon::callbacks;
use corvid_rewrite::Rewriter;
use corvid_sign::Signer;
use indymilter::Config;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};

// What Stalwart 0.16.21 offers: every action flag, and NOHELO|NOEOH.
const MTA_ACTIONS: u32 = 0xff;
const MTA_OPTS: u32 = 0x42;

const ADD_HEADER: u32 = 0x01;
const REPLACE_BODY: u32 = 0x02;

async fn negotiate(actions: u32, opts: u32) -> (u32, u32, u32) {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = listener.local_addr().unwrap();
    let rewriter = Arc::new(Rewriter::new(
        Signer::new(b"t".to_vec()),
        "https://img.test",
    ));
    tokio::spawn(async move {
        indymilter::run(
            listener,
            callbacks(rewriter),
            Config::default(),
            future::pending::<()>(),
        )
        .await
    });

    let mut stream = TcpStream::connect(addr).await.unwrap();
    let mut packet = vec![b'O'];
    packet.extend_from_slice(&6u32.to_be_bytes());
    packet.extend_from_slice(&actions.to_be_bytes());
    packet.extend_from_slice(&opts.to_be_bytes());
    stream
        .write_all(&(packet.len() as u32).to_be_bytes())
        .await
        .unwrap();
    stream.write_all(&packet).await.unwrap();

    let mut reply = [0u8; 17];
    stream
        .read_exact(&mut reply)
        .await
        .expect("milter closed without negotiating");
    assert_eq!(reply[4], b'O', "expected an OPTNEG reply");

    let word = |at: usize| u32::from_be_bytes(reply[at..at + 4].try_into().unwrap());
    (word(5), word(9), word(13))
}

#[tokio::test]
async fn negotiates_with_what_stalwart_offers() {
    let (version, actions, opts) = negotiate(MTA_ACTIONS, MTA_OPTS).await;

    assert_eq!(version, 6);
    assert_eq!(
        actions,
        ADD_HEADER | REPLACE_BODY,
        "only the actions the daemon uses"
    );
    assert_eq!(
        opts & !MTA_OPTS,
        0,
        "must not request an option the MTA did not offer"
    );
}

// An MTA offering no protocol options is read as offering the minimum set, so the reply
// carries those rather than nothing.
const MIN_OPTS: u32 = 0xf;

#[tokio::test]
async fn negotiates_with_an_mta_that_offers_no_protocol_options() {
    let (_, actions, opts) = negotiate(MTA_ACTIONS, 0).await;

    assert_eq!(actions, ADD_HEADER | REPLACE_BODY);
    assert_eq!(
        opts & !MIN_OPTS,
        0,
        "an MTA offering nothing must still negotiate"
    );
}
