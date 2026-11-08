# SunShine

Плагин для Paper/Leaf: связь Minecraft-сервера с Discord (привязка аккаунтов, заявки на сервер, чат и события через вебхуки, наказания из LiteBans, суд, общины на карте BlueMap, тикеты поддержки).

## Сборка

Нужны JDK 25 и Maven.

```bash
mvn clean package
```

Плагин зависит от LiteBans API. Скачайте LiteBans и положите его jar в `libs/LiteBans.jar` (файл в репозиторий не входит из-за лицензии).

## Настройка

Все токены ботов и пароль базы данных задаются только в `plugins/SunShine/config.yml` на сервере. В репозитории лежит шаблон `src/main/resources/config.yml` с пустыми значениями. Свои токены и пароли в репозиторий не добавляйте.

## Документация

- [GUIDE.md](GUIDE.md) — руководство по настройке
- [PLAYER_GUIDE.md](PLAYER_GUIDE.md) — для игроков
- [TERRITORY_SETUP.md](TERRITORY_SETUP.md) — общины и карта
