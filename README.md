# kfcudp-instant-p2p

> P2P 기반의 간편한 친구 초대 멀티플레이 모드 · A lightweight P2P multiplayer invite mod for Minecraft

![QUIC P2P](https://img.shields.io/badge/QUIC-P2P-4caf50?style=flat-square)
![Seoul Oracle Cloud](https://img.shields.io/badge/Server-Seoul%20Oracle%20Cloud-1976d2?style=flat-square)
![Invite Code](https://img.shields.io/badge/방식-초대코드-00897b?style=flat-square)

---

## 📋 개요 / Overview

### 🇰🇷 한국어

이 모드는 **26.2-snapshot7**에서 실험된 P2P 멀티플레이 기능을 백포팅하여, 성능 개선·경량화·기능 간소화를 적용한 커스텀 버전입니다.

기존의 친구 추가·허가 절차를 완전히 제거하고, **e4mc**와 유사한 방식으로 초대코드 하나만으로 즉시 접속할 수 있습니다.
시그널링·STUN·TURN 서버는 **서울 오라클 클라우드**에 위치합니다.

### 🇺🇸 English

This mod is a custom, performance-optimized and slimmed-down, backporting the P2P multiplayer feature originally experimented in **26.2-snapshot7**.

The traditional friend-request and approval flow has been completely removed. Just like **e4mc**, a simple invite code is all you need to connect.
Signaling, STUN, and TURN servers are hosted on **Oracle Cloud Seoul**.

---

## 🚀 사용 방법 / How to Use

### 🖥️ 호스트 (방 만들기) / Host — Create a Room

1. **싱글플레이 세계에 접속 / Enter singleplayer world**
   기존에 플레이하던 싱글플레이 세계를 엽니다.
   Open your existing singleplayer world.

2. **우측 상단 버튼 클릭 / Click top-right button**
   화면 우측 상단의 **"커스텀 방 만들기"** 버튼을 클릭합니다.
   Click the **"Create Custom Room"** button in the top-right corner of the screen.

3. **초대코드 공유 / Share the invite code**
   생성된 초대코드를 접속자에게 전달합니다.
   Share the generated invite code with your friends.

> 방을 열려면 **정품 계정**이 필요합니다. 접속은 계정과 상관없이 됩니다.
> Opening a room requires a **premium (Mojang-verified) account**. Joining works with any account.

---

### 🎮 접속자 (방 들어가기) / Client — Join a Room

1. **멀티플레이 화면으로 이동 / Go to multiplayer screen**
   타이틀 화면에서 **멀티플레이**를 선택합니다.
   Select **Multiplayer** from the title screen.

2. **"커스텀 방 들어가기" 클릭 / Click "Join Custom Room"**
   멀티플레이 화면에서 **"커스텀 방 들어가기"** 버튼을 클릭합니다.
   Click the **"Join Custom Room"** button on the multiplayer screen.

3. **초대코드 입력 / Enter the invite code**
   호스트에게 받은 초대코드를 입력하면 즉시 접속됩니다.
   Enter the invite code from the host to connect instantly.

---

## 🛡️ 방 관리 기능 / Room Management

### 🇰🇷 한국어

초대코드 하나로 누구나 접속할 수 있는 대신, 호스트가 방을 직접 통제할 수 있는 수단을 함께 제공합니다.

- **화이트리스트** — `/whitelist on`으로 켜면 등록된 플레이어만 입장할 수 있습니다. `/whitelist add|remove <닉네임>`으로 관리하고, `/whitelist list`로 목록을 확인합니다.
- **밴 / 킥** — `/ban <닉네임>`, `/ban-ip <닉네임>`(우회 재접속 차단), `/kick <닉네임>`, 해제는 `/pardon`·`/pardon-ip`. P2P 터널을 지나면 모든 접속자가 겉보기엔 같은 로컬 주소로 보이지만, 실제 원격 IP를 별도로 추적해 IP 밴이 정확히 동작합니다.
- **연결 경로 알림** — 각 플레이어가 직결(Direct)로 붙었는지, 중계 서버(Relay)를 거쳤는지 참여 메시지에 자동으로 표시됩니다. 접속자 본인에게도 월드 진입 시 알려줍니다. 네트워크 환경에 따른 지연 차이를 바로 파악할 수 있습니다.

이 명령어들은 방장(싱글플레이 소유자)이 실행할 수 있으며, 방을 열 때마다 자동으로 등록됩니다.

### 🇺🇸 English

Since anyone with the invite code can join, the host is given real tools to keep the room under control.

- **Whitelist** — Turn it on with `/whitelist on` to only allow registered players in. Manage it with `/whitelist add|remove <name>`, and check it with `/whitelist list`.
- **Ban / Kick** — `/ban <name>`, `/ban-ip <name>` (blocks reconnects via a new account), `/kick <name>`, and `/pardon` / `/pardon-ip` to undo. Every guest tunneled through P2P would normally look like it's coming from the same local address, but the mod tracks each guest's real remote IP separately so IP bans work correctly.
- **Connection-type indicator** — Whether each player connected directly (Direct) or through the relay server (Relay) is shown automatically in the join message, and joiners are told their own connection type when they enter the world — handy for spotting network-related latency differences at a glance.

These commands are available to the host (the singleplayer world owner) and are (re-)registered automatically whenever a room is opened.

---

## ⚙️ 기술 사양 / Technical Details

| 항목 / Item | 내용 / Details |
|---|---|
| 연결 방식 / Connection | QUIC 기반 P2P (홀펀칭 + 중계 폴백) / QUIC-based P2P (hole punching + relay fallback) |
| 서버 위치 / Server Region | 서울, 한국 / Seoul, South Korea |
| 서버 인프라 / Infrastructure | Oracle Cloud |
| 서버 구성 / Server Stack | Signaling + STUN + TURN |
