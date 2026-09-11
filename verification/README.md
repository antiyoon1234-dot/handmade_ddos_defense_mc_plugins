# 로컬 서버 검증

Node.js 22 이상 필요. 이 폴더에서 `npm ci --ignore-scripts`로 테스트 클라이언트 의존성을 설치합니다. 설치용 플러그인은 Node.js를 사용하지 않습니다.

직접 준비한 테스트 Paper 서버만 사용하세요. 테스트 클라이언트는 주소를 `127.0.0.1`로 고정하고 포트 25585/25586만 허용합니다. 플레이 검증용 테스트 서버는 `server-ip=127.0.0.1`, `online-mode=false`로 설정합니다. 운영 서버 설정에 복사하지 마세요. ProtocolLib는 공식 릴리스 설치 JAR이 필요하며 Maven 저장소의 API JAR을 서버에 넣지 않습니다.

## 명령/패킷 제한

테스트 설정: `violations.minimum-span-seconds: 1`, `violations.kick-after: 2`, 검사할 요청의 `per-second: 0.1`, `burst: 1`. 나머지는 기본값을 유지합니다. 기존 유저와 과다 요청 유저를 같은 IP로 접속시켜 추방 문구, 기존 연결 유지, 재접속을 확인합니다.

```sh
node smoke.js 1.20.1 25585 result.json 250 command
node smoke.js 1.20.6 25586 result.json 250 command
node smoke.js 1.20.6 25586 result.json 250 payload
```

`payload`는 ProtocolLib 설치가 필요합니다. 낮춘 수치를 운영 설정에 그대로 적용하지 마세요.

## 로그인 처리량

테스트 설정: `login.per-second: 0.1`, `login.protected-per-second: 0.1`, `login.burst: 1`. 새로 reload한 뒤 실행합니다.

```sh
node login-limit.js 1.20.1 25585
```

## 미완료 로그인

1.20.1의 로컬 테스트 서버에서 `online-mode=true`, `login.max-pending: 1`, `login.pending-timeout-seconds: 10`으로 바꾸고 재시작합니다. 로그인 속도 제한은 기본값으로 복구합니다. 테스트 연결은 암호화 응답을 전송하지 않습니다.

```sh
node pending-login.js
```

진행 중 로그인 수 상한, 시간 초과 안내, 슬롯 회복을 확인합니다. 전체 정품 인증이나 실제 대규모 DDoS 시험은 아닙니다.
