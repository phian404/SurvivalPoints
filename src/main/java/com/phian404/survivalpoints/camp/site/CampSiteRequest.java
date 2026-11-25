package com.phian404.survivalpoints.camp.site;

import net.minecraft.core.BlockPos;

/**
 * Источник запроса на создание лагеря.
 *
 * <p>{@link Source#MANUAL_RADIO} — игрок поставил радио или команда создала
 * лагерь вручную. Для этого пути сохраняются прежние исключения: колонка
 * исходного радио пропускается при поиске грунта и при проверке зоны подготовки.
 *
 * <p>{@link Source#AUTO_CELL} — автоматический кандидат из процедурной ячейки.
 * Радио в точке запроса нет, поэтому исключения не применяются: грунт ищется
 * во всех колонках, а любая колонка с {@code BlockEntity} блокирует площадку.
 */
public record CampSiteRequest(Source source, BlockPos requestPos, int radius) {

    public enum Source {
        MANUAL_RADIO,
        AUTO_CELL
    }
}
