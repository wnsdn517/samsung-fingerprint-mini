# Samsung Fingerprint Mini

삼성 지문 인증창을 지문 표시만 남기고 축소하는 Xposed(LSPosed) 모듈입니다. 인증창 뒤의 앱이 그대로 보이고 터치도 됩니다. 시스템 지문 인증을 쓰는 모든 앱(카카오톡, 은행 앱 등)에서 동작합니다.

Xposed (LSPosed) module that shrinks Samsung's biometric prompt down to just the fingerprint indicator. Works in any app that uses the system fingerprint prompt.

## 기능
- 인증창 축소: 지문만 / 메시지 + 지문 / 간소화 / 끄기
- 뒤의 앱 터치 허용
- 화면 내장 센서가 아닌 기기(측면 전원 버튼, 후면)에서 센서 위치에 상태별 빛 애니메이션 (대기 / 인식 중 / 성공 / 실패)
- 포팅 롬처럼 센서 종류가 잘못 보고되는 기기 자동 보정

## 설치
1. [Releases](../../releases)에서 APK 설치
2. LSPosed에서 모듈 활성화, 범위: `com.samsung.android.biometrics.app.setting`
3. 해당 패키지 강제 종료 또는 재부팅

Android 10 이상, One UI, LSPosed 필요.

## 설정
앱 서랍의 **Fingerprint Mini** 앱(또는 LSPosed의 모듈 설정)에서 바꿉니다. 설정은 저장되어 **재부팅 후에도 유지**되고, 다음 인증창부터 바로 적용됩니다 (재시작 불필요).

| 키 | 값 | 기본값 | 설명 |
|---|---|---|---|
| `sensor` | `auto` `side` `udfps` `rear` | `auto` | 기기 형식 (SideFPS = 측면 전원 버튼, UDFPS = 화면 내장) |
| `layout` | `minimal` `message` `compact` `off` | `minimal` | 지문만 / 메시지 + 지문 / 간소화 / 삼성 기본 |
| `anim` | `1` `0` | `1` | 지문 애니메이션 (`0` = 삼성 기본) |
| `touch` | `1` `0` | `1` | 뒤의 앱 터치 허용 (켜면 인증창의 취소 버튼 등은 누를 수 없음) |
| `side_y` | `0`–`60` | `0` | SideFPS 센서 높이, 화면 높이의 % (`0` = 자동) |
| `side_len` | `0`–`30` | `0` | SideFPS 센서 길이, 화면 높이의 % (`0` = 자동) |
| `hold` | `1` `0` | `1` | 성공/실패 애니메이션을 잠깐 보여준 뒤 닫기 |
| `timeout` | 초 | `30` | 시간 초과 표시까지의 시간 |

## 지문 표시가 잘못 나올 때 (adb)
센서 종류나 위치가 틀리게 표시되면(예: 측면 센서 기기인데 빛이 안 나옴, 위치가 어긋남) adb로 직접 설정하세요.

**재부팅 후에도 유지** (앱 설정과 같은 값을 바꿈):
```sh
# 측면 전원 버튼 센서로 지정
adb shell am broadcast -a io.github.fpmini.SET -n io.github.fpmini/.ConfigReceiver --include-stopped-packages --es sensor side

# 여러 개 한 번에: 센서 높이 25%, 메시지 + 지문
adb shell am broadcast -a io.github.fpmini.SET -n io.github.fpmini/.ConfigReceiver --include-stopped-packages --es side_y 25 --es layout message

# 현재 설정 보기 (값 없이 실행)
adb shell am broadcast -a io.github.fpmini.SET -n io.github.fpmini/.ConfigReceiver --include-stopped-packages

# 기본값으로 초기화
adb shell am broadcast -a io.github.fpmini.SET -n io.github.fpmini/.ConfigReceiver --include-stopped-packages --ez reset true
```
결과에 현재 설정이 `data="sensor=side layout=minimal ..."` 형태로 출력됩니다.

**임시 (재부팅하면 사라짐, 테스트용)**: 같은 키를 `debug.fpmini.<키>` 속성으로 설정하면 저장된 설정보다 우선합니다.
```sh
adb shell setprop debug.fpmini.sensor side
adb shell setprop debug.fpmini.side_y 25
adb shell setprop debug.fpmini.sensor ""   # 해제
```
`debug.fpmini.demo 1`은 손가락 없이 모든 애니메이션 상태를 반복 재생합니다.

`persist.*` 속성은 루트 없이 설정할 수 없어서 위의 broadcast 방식을 씁니다.

## 문제 확인
```sh
adb logcat -s FpMini
```
`glow SIDE centre=...` 로그가 나오면 빛 표시가 붙은 것이고, `fingerprint features: ...`는 롬이 보고한 센서 종류입니다.

## 빌드
```sh
./gradlew :module:assembleDebug
```
`testapp`은 지문 인증창을 띄우는 테스트 앱이고, `xposed-stub`은 컴파일 전용 Xposed API 스텁입니다.
