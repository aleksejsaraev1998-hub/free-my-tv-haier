# До Bento

Контрольная точка исходников и инструментов: ветка `backup/pre-bento-preview-3`, commit `466aaccc45fac1a58e1e4d4a1e00464d230fe894`.

Сборка Standard/Lite до переноса: https://github.com/aleksejsaraev1998-hub/free-my-tv-haier/actions/runs/40099086900. Artifacts имеют ограниченный срок хранения; установленный владельцем APK уже скачан на его ПК под именем FreeMyTV-Haier-lite-Preview-universal.apk. Не перезаписывать его APK с другим signing key.

Bento имеет другой package ID и ставится рядом с Lite. Возврат к прежнему виду не требует переустановки: закрыть Bento и открыть Lite. Штатный лаунчер продолжает работать по Home. Приватные signing keys, настройки Wi-Fi и диагностические логи в репозиторий не включены.
