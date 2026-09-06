# TikTok-RV — compatibility fork

[![Release build](https://img.shields.io/github/actions/workflow/status/Pupokman/tiktok-rv_testing/build-release.yml?branch=main&label=release%20build)](https://github.com/Pupokman/tiktok-rv_testing/actions/workflows/build-release.yml)
[![Latest release](https://img.shields.io/github/v/release/Pupokman/tiktok-rv_testing)](https://github.com/Pupokman/tiktok-rv_testing/releases/latest)
[![Downloads](https://img.shields.io/github/downloads/Pupokman/tiktok-rv_testing/total)](https://github.com/Pupokman/tiktok-rv_testing/releases)

> **Это форк, а не оригинальный TikTok-RV.** Исходный проект: [thelok1s/tiktok-rv](https://github.com/thelok1s/tiktok-rv). Этот репозиторий появился как совместимый форк для свежих версий TikTok, когда upstream-сборки начали ломаться на новой структуре split APK.

Форк сохраняет исходный набор ReVanced-патчей, но исправляет саму сборку Universal APK: выбирается настоящий base APK, после патчинга возвращаются все необходимые split-модули, а релизы подписываются одним постоянным ключом.

Текущая рабочая база протестирована на Android-эмуляторе: установка, запуск и функции модифицированного TikTok работают.

## Скачать и установить

Готовые APK находятся в [Releases](../../releases).

Скачайте `tiktok-rv-original.apk` и установите его как обычный APK. SAI и ручная установка split APK не нужны.

Если у вас стоит одна из ранних тестовых сборок этого форка, удалите её перед первой установкой релизной версии: тестовые APK подписывались одноразовыми ключами. После перехода на релизную подпись следующие версии можно будет устанавливать поверх текущей как обычное обновление.

## Что исправлено в этом форке

Свежие версии TikTok распространяются набором split APK. В старой логике сборки появились две проблемы:

- в качестве base APK мог ошибочно выбираться крупный dynamic-feature split вроде `df_a_dex.apk`;
- после патчинга при merge сохранялись только `config.*` splits, хотя TikTok также требует dynamic-feature, dex, asset и resource splits во время запуска.

В результате часть APK не устанавливалась, а часть могла показать splash screen и сразу завершиться.

Здесь схема сборки другая:

1. Находится и проверяется настоящий `com.zhiliaoapp.musically` base APK.
2. ReVanced патчит только base.
3. Пропатченный base заменяет оригинальный base.
4. Все остальные исходные splits возвращаются без потерь.
5. APKEditor собирает единый APK, после чего он подписывается и проверяется `apksigner`.

## Патчи

В сборку входят:

- меню ReVanced в настройках TikTok;
- фильтрация рекламы в ленте и автопропуск части рекламных вставок;
- отключение обязательного экрана входа;
- скачивание видео и удаление watermark там, где это позволяет текущая серверная логика TikTok;
- управление скоростью воспроизведения;
- принудительный seekbar;
- запоминание Clear Display;
- подмена SIM-региона, по умолчанию на Латвию.

Патчи находятся в `revanced-patches` и собираются из исходников внутри репозитория.

## Вход в аккаунт

Из-за патча отключения обязательного входа стандартные кнопки авторизации могут работать нестабильно. Если обычный вход не проходит:

1. Откройте **Need help logging in?** / **Recover Your Account**.
2. Укажите почту, username или номер телефона.
3. Введите полученный код.
4. После сообщения об успешном входе перезапустите приложение.

## Подпись релизов

Все релизные сборки этого форка подписываются одним постоянным ключом.

SHA-256 сертификата:

```text
B2:6B:CE:54:24:44:C1:26:40:48:57:C1:4D:36:47:0A:F3:24:52:E9:CE:D4:0A:34:A1:0C:FF:FB:47:10:1F:0F
```

Workflow проверяет fingerprint перед публикацией. Если ключ не совпадает, релизная сборка завершается ошибкой.

## Сборка

Релизный pipeline находится в `.github/workflows/build-release.yml` и делает следующее:

1. загружает актуальный TikTok из Google Play через `gplaydl` с fallback на APKPure через `apkeep`;
2. собирает локальное дерево ReVanced patches;
3. патчит base APK через `revanced-cli`;
4. объединяет его со всеми исходными non-base splits через APKEditor;
5. восстанавливает signing key из GitHub Actions Secret;
6. подписывает и проверяет APK;
7. публикует artifact и GitHub Release.

Приватный signing key в репозиторий не коммитится.

## Upstream и лицензия

Этот форк основан на [thelok1s/tiktok-rv](https://github.com/thelok1s/tiktok-rv) и использует инструменты и код экосистемы ReVanced.

Также используются:

- [gplaydl](https://github.com/rehmatworks/gplaydl)
- [revanced-cli](https://github.com/ReVanced/revanced-cli)
- [revanced-patcher](https://github.com/ReVanced/revanced-patcher)
- [APKEditor](https://github.com/REAndroid/APKEditor)

Код патчей в `revanced-patches` распространяется по GPLv3; см. [LICENSE](LICENSE).

---

## English

This is a **compatibility fork**, not the original TikTok-RV project. Upstream: [thelok1s/tiktok-rv](https://github.com/thelok1s/tiktok-rv).

The fork keeps the same ReVanced-oriented patch set but fixes packaging for recent TikTok split-APK layouts:

- selects and validates the real TikTok base APK;
- patches only the base;
- preserves every required non-base split before the universal merge;
- signs releases with one persistent key so future versions can update in place;
- verifies the release certificate before publishing.

Download the current APK from [Releases](../../releases). If you installed an earlier test build from this fork, uninstall it once before installing the first release-signed build because those tests used disposable keys.

Release certificate SHA-256:

```text
B2:6B:CE:54:24:44:C1:26:40:48:57:C1:4D:36:47:0A:F3:24:52:E9:CE:D4:0A:34:A1:0C:FF:FB:47:10:1F:0F
```

The current build has been tested on an Android emulator and installs, launches and runs the patched TikTok functionality correctly.
