package com.phian404.survivalpoints.client;

import com.phian404.survivalpoints.ModModelLayers;
import com.phian404.survivalpoints.entity.BaseSurvivorEntity;
import com.phian404.survivalpoints.entity.role.SurvivorGender;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ArmorItem;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.EnumSet;

/**
 * Модель выжившего с выступом торса для женского пола.
 *
 * <p>Геометрия строится из ванильного меша игрока, поэтому SLIM/WIDE-разница
 * рук сохраняется. Единственное добавление — четыре child-сегмента
 * {@code chest_seg0..3} у {@code body}: два слева, два справа, встык по центру,
 * и два капа нижней грани {@code chest_under0..1}.
 *
 * <p><b>UV.</b> Все сегменты читают переднюю грань {@code body}
 * ({@code u 20..28, v 20..32}) непрерывно: {@code texOffs} сдвигается на
 * {@code width} за сегмент, поэтому выступ совпадает по цвету с торсом на любом
 * скине, а стыки не видны. Верхняя грань отключена через {@code visibleFaces}:
 * её UV попадает в строку воротника и даёт полосы. Нижняя грань рисуется
 * отдельными капами со своим {@code texOffs}. Параметры формы — константы
 * {@link SurvivorChestTuning}. Подробности — в javadoc {@link #chestLayer(boolean)}.
 */
@Mod.EventBusSubscriber(modid = "survivalpoints", bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class SurvivorBodyModel<T extends BaseSurvivorEntity> extends HumanoidModel<T> {

    /** Имена segments {@code body}. Должны совпадать с {@code chestLayer}. */
    public static final String[] SEGMENTS = {
            "chest_seg0", "chest_seg1", "chest_seg2", "chest_seg3"
    };

    /**
     * Имена капов нижней грани: по одному кубу на левую и правую детали.
     * Отдельные кубы нужны потому, что {@code CubeListBuilder} не умеет
     * задавать отдельный UV одной грани внутри куба.
     */
    public static final String[] UNDERSIDES = {"chest_under0", "chest_under1"};

    private final ModelPart[] segments;
    private final ModelPart[] undersides;

    public SurvivorBodyModel(ModelPart root) {
        super(root);
        ModelPart body = root.getChild("body");
        this.segments = new ModelPart[SEGMENTS.length];
        for (int i = 0; i < SEGMENTS.length; i++) {
            this.segments[i] = body.getChild(SEGMENTS[i]);
        }
        this.undersides = new ModelPart[UNDERSIDES.length];
        for (int i = 0; i < UNDERSIDES.length; i++) {
            this.undersides[i] = body.getChild(UNDERSIDES[i]);
        }
    }

    /**
     * Меш выжившего с сегментированным выступом торса.
     *
     * <p><b>Почему сегменты.</b> Старый однокубовый вариант не давал нужной
     * формы, поэтому деталь собирается из четырёх кубов по {@code width<=2}:
     * два слева, два справа, встык по центру торса.
     *
     * <p><b>Непрерывный UV.</b> Раньше все четыре куба использовали
     * {@code texOffs(20,20)}. Это и было причиной полос: {@code addBox}
     * раскладывает UV куба по шаблону «крест», поэтому грань {@code NORTH}
     * читает не один тексель, а колонку {@code u+d..u+d+w}.
     * При {@code w=2, d=1} все четыре сегмента читали одну и ту же колонку
     * {@code u 21..23} — четыре одинаковых вертикальных полосы подряд, а
     * {@code UP}/{@code DOWN} добавляли вторую полосу из строки {@code v 20..21}.
     *
     * <p>Теперь {@code texOffs} сдвигается на ширину сегмента:
     * {@code u = 20 + i*width - depth}. При {@code width=2} грани {@code NORTH}
     * четырёх кубов читают {@code 20..22, 22..24, 24..26, 26..28} — ровно
     * переднюю грань {@code body} ({@code u 20..28}), без повторов и разрывов.
     * Шов между сегментами становится продолжением рисунка торса. {@code v}
     * сдвинут на {@code -depth}, чтобы выборка начиналась под каймой воротника.
     * При расширении выступа суммарная ширина выходит за 8 px передней грани —
     * {@code NORTH} тогда обрезается по правому краю текстуры.
     *
     * <p><b>Какая грань смотрит вниз.</b> В модель-пространстве {@code Y+}
     * направлен вниз (у ванильного торса куб занимает {@code y 0..12}, ноги
     * стоят на {@code y=12}), а {@code ModelPart.Cube} строит грани по
     * плоскостям {@code minY}/{@code maxY}: {@link Direction#DOWN} — плоскость
     * {@code minY}, {@link Direction#UP} — {@code maxY}. То есть {@code DOWN}
     * визуально верхняя грань, а {@code UP} — нижняя. Названия инвертированы
     * относительно картинки; эталон — развёртка головы: при {@code texOffs(0,0)}
     * слот {@code DOWN} занимает {@code (8,0)-(16,8)}, то есть макушку, а
     * {@code UP} — {@code (16,0)-(24,8)}, подбородок.
     *
     * <p><b>Нижняя грань — это {@code UP}.</b> Она лежит на
     * {@code y = offsetY + height}, то есть на уровне груди: пивот стоит в
     * {@code offsetY}, куб растёт вниз на {@code height}, никаких смещений и
     * инверсии наклона нет. {@code DOWN} остаётся выключенным: его UV приходится
     * на строку над {@code NORTH} ({@code v..v+depth}) — на торсе это воротник,
     * источник горизонтальных полос.
     *
     * <p><b>UV нижней грани.</b> Слот {@code UP} занимает
     * {@code u+d+w .. u+d+2w}, {@code v .. v+depth} — ширину сегмента справа от
     * {@code NORTH}. Сдвигать {@code texOffs} ради неё нельзя: он общий для всех
     * граней куба, и {@code NORTH} сразу уедет с передней грани торса. Поэтому
     * нижняя грань рисуется отдельными капами {@code chest_under0..1} со своим
     * {@code texOffs} (см. {@link #addUnderside(PartDefinition, String, float, float, float, float, float, int)}).
     * При {@code depth=3.0} (целое) слоты капов читают ровно {@code u 20..24}
     * и {@code 24..28}, {@code v 24..27} — передняя грань торса, целые границы.
     *
     * <p><b>Порядок слева направо.</b> Сегмент {@code i} стоит по X в
     * {@code offsetX - width/2} первой половины детали и
     * {@code offsetX + width/2} — второй. При {@code offsetXL = -2},
     * {@code offsetXR = +2} и {@code width = 2} получается сплошная полоса
     * {@code x -4..+4}: вся ширина торса, соединённая в центре.
     * UV считается по индексу, поэтому порядок сегментов по X должен
     * возрастать: {@code offsetXL < offsetXR}.
     *
     * <p><b>Внутренние грани.</b> На стыках сегментов боковые грани
     * отключаются ({@code visibleFaces} без WEST/EAST у средних), иначе на
     * копланарных плоскостях появляется z-fighting. Внешние торцы закрыты.
     *
     * <p>Параметры берутся из {@link SurvivorChestTuning} — константы, поэтому
     * меш строится один раз при регистрации рендерера.
     */
    public static LayerDefinition chestLayer(boolean slim) {
        MeshDefinition mesh = PlayerModel.createMesh(CubeDeformation.NONE, slim);
        PartDefinition body = mesh.getRoot().getChild("body");

        float width = SurvivorChestTuning.width();
        float height = SurvivorChestTuning.height();
        float depth = SurvivorChestTuning.depth();
        float half = width / 2.0F;

        // Четыре позиции по X: два сегмента левой детали, два — правой.
        // Внутренние торцы (стык x=0) не рисуются: они совпадают по плоскости.
        //
        // Внешние сегменты (i==0, i==3) уже ширины width-0.1 и сдвинуты внутрь
        // на 0.05: их боковины уходят с x=±3 на x=±2.9 и гарантированно не
        // касаются торса (x=±4). Стыки seg0/seg1 и seg2/seg3 остаются встык
        // без перекрытия и щели: seg0.EAST=-2.0 == seg1.WEST=-2.0, seg2.EAST=2.0
        // == seg3.WEST=2.0.
        float[] offsets = {
                SurvivorChestTuning.offsetXLeft() - half + 0.05F,
                SurvivorChestTuning.offsetXLeft() + half,
                SurvivorChestTuning.offsetXRight() - half,
                SurvivorChestTuning.offsetXRight() + half - 0.05F
        };
        for (int i = 0; i < offsets.length; i++) {
            float tilt = i < 2 ? SurvivorChestTuning.tiltLeft() : SurvivorChestTuning.tiltRight();
            // Внешние сегменты уже на 0.1: боковины уходят внутрь от торса.
            float segWidth = (i == 0 || i == 3) ? width - 0.1F : width;
            // Боковины открыты только на внешних торцах детали: i==0 — левый
            // край полосы, i==3 — правый. Внутренние стыки (i==1, i==2) закрыты:
            // их грани обращены внутрь детали и не видны снаружи.
            addSegment(body, SEGMENTS[i], i, segWidth, height, depth,
                    offsets[i], tilt, i == 0, i == offsets.length - 1);
        }
        // Нижние грани отдельными кубами: свой texOffs, не связанный с NORTH.
        // Кап покрывает всю деталь (два сегмента) и сдвинут вместе с ней внутрь.
        // capWidth=4.0 — целый, чтобы слот UP по U был ровно 20..24 и 24..28
        // (без дробных границ). capDepth = depth сегментов (1.3): кап должен
        // совпадать с сегментами по глубине, иначе при наклоне его нижняя грань
        // уходит внутрь объёма торса и закрывается z-буфером. Нижняя плоскость
        // капа (maxY = offsetY + height) не зависит от capDepth.
        float partWidth = 4.0F;
        float partDepth = depth;
        addUnderside(body, UNDERSIDES[0], partWidth, height, partDepth,
                SurvivorChestTuning.offsetXLeft() + 0.05F, SurvivorChestTuning.tiltLeft(), 0);
        addUnderside(body, UNDERSIDES[1], partWidth, height, partDepth,
                SurvivorChestTuning.offsetXRight() - 0.05F, SurvivorChestTuning.tiltRight(), 1);

        return LayerDefinition.create(mesh, 64, 64);
    }

    /**
     * Добавляет один сегмент выступа.
     *
     * @param index     позиция сегмента слева направо; задаёт сдвиг UV, чтобы
     *                  {@code NORTH} четырёх кубов образовали непрерывную
     *                  полосу передней грани торса
     * @param openWest  рисовать западную грань; {@code false} — сегмент стыкуется
     *                  с соседом слева, грань отключена
     * @param openEast  то же для восточной грани
     */
    private static void addSegment(PartDefinition body, String name, int index,
                                   float width, float height, float depth,
                                   float offsetX, float tilt,
                                   boolean openWest, boolean openEast) {
        // NORTH — передняя грань. Нижняя грань здесь НЕ рисуется: её слот UP
        // (u+d+w .. u+d+2w) не помещается в переднюю грань торса рядом со
        // NORTH (см. addUnderside). DOWN визуально верхний и попадает в строку
        // воротника, SOUTH прижат к торсу.
        EnumSet<Direction> faces = EnumSet.of(Direction.NORTH);
        if (openWest) {
            faces.add(Direction.WEST);
        }
        if (openEast) {
            faces.add(Direction.EAST);
        }

        // UV считается по целым пикселям: дробный depth давал субпиксельный
        // старт NORTH, из-за чего рисунок торса интерполировался и уезжал.
        // Геометрия остаётся дробной, выравнивается только развёртка.
        int pixelW = Math.max(1, Math.round(width));
        int pixelD = Math.max(1, Math.round(depth));

        // NORTH занимает u+d .. u+d+w. Сдвиг по width за сегмент даёт стык без
        // зазора: u 20..28 — ровно передняя грань торса.
        int u = 20 + index * pixelW - pixelD;

        // Верх NORTH соответствует высоте детали над торсом: на текстуре
        // v = 20 + y (передняя грань торса занимает v 20..32), а NORTH
        // начинается на v+depth. Отсюда v = 20 + offsetY - depth.
        int v = Math.max(19, Math.round(19.0F + SurvivorChestTuning.offsetY() - pixelD));

        body.addOrReplaceChild(name, CubeListBuilder.create()
                        .texOffs(u, v)
                        .addBox(-width / 2.0F, 0.0F, -depth, width, height, depth, faces),
                PartPose.offsetAndRotation(offsetX, SurvivorChestTuning.offsetY(),
                        SurvivorChestTuning.offsetZ(), tilt, 0.0F, 0.0F));
    }

    /**
     * Добавляет кап нижней грани — куб-копию геометрии детали с единственной
     * гранью {@link Direction#UP}.
     *
     * <p>{@code CubeListBuilder} в 1.20.1 задаёт один {@code texOffs} на весь
     * куб, поэтому нижнюю грань нельзя снабдить своим UV внутри сегмента.
     * Отдельный куб с той же {@link PartPose} и теми же размерами даёт ровно
     * ту же плоскость {@code maxY}, но свободный {@code texOffs}. Правая деталь
     * {@code index=1} сдвинута на ширину капа — слоты стыкуются без разрыва.
     *
     * <p>{@code UP} читает {@code u+d+w .. u+d+2w}, {@code v .. v+d}. При
     * {@code width=2}, {@code depth=1.3} слоты капов дают {@code u 20.3..24.3}
     * и {@code 24.3..28.3} — передняя грань торса, без выхода в боковину (старый
     * вариант с {@code UP} внутри сегмента уезжал в {@code u 28..30}).
     * {@code v} — строка торса под деталью: {@code 20 + offsetY + height}.
     *
     * <p>ponytail: дробный {@code depth} сдвигает край слота примерно на 0.3 px
     * (20.3 вместо 20.0); грани с nearest-фильтрацией это даёт один растянутый
     * тексель на нижней плоскости, снизу незаметно. Целый {@code depth} убирает
     * погрешность полностью.
     */
    private static void addUnderside(PartDefinition body, String name,
                                     float capWidth, float height, float depth,
                                     float offsetX, float tilt, int index) {
        // Слот UP читает u+depth+width .. u+depth+2*width, v .. v+depth.
        // При дефолте depth=3.0 (целое): слот по U = u+7 .. u+11. uvU = 13 + index*4
        // даёт слоты ровно 20..24 (левый) и 24..28 (правый) — передняя грань
        // торса, целые границы, без растяжения и смещения. По V слот = v .. v+3:
        // uvV = round(20 + offsetY + height - depth) + 1 = 24, слот 24..27 —
        // сетка смещена на 1 px вниз (строка торса y 4..7).
        int uvU = 13 + index * 4;
        int uvV = Math.min(31, Math.max(20,
                Math.round(20.0F + SurvivorChestTuning.offsetY() + height - depth) + 1));

        // mirror() разворачивает порядок вершин полигона UP, переворачивая
        // текстуру нижней грани по U и V. Чистого V-переворота в 1.20.1 нет
        // (texOffs общий для куба, кастомный полигон недоступен), поэтому
        // принимаем переворот по обеим осям: на однотонной ткани торса
        // переворот по U незаметен. Положение капа не меняется — mirror
        // только меняет роли WEST/EAST, а грань UP остаётся на плоскости maxY.
        body.addOrReplaceChild(name, CubeListBuilder.create()
                        .texOffs(uvU, uvV)
                        //.mirror()
                        .addBox(-capWidth / 2.0F, 0.0F, -depth, capWidth, height, depth,
                                EnumSet.of(Direction.UP)),
                PartPose.offsetAndRotation(offsetX, SurvivorChestTuning.offsetY(),
                        SurvivorChestTuning.offsetZ(), tilt, 0.0F, 0.0F));
    }

    @SubscribeEvent
    public static void registerLayerDefinitions(EntityRenderersEvent.RegisterLayerDefinitions event) {
        // Стартовые слои: из них ванильный ModelManager печёт модели при загрузке.
        // Дальнейшие изменения параметров пересобирают меш через chestLayer
        // напрямую и в этот реестр не попадают.
        event.registerLayerDefinition(ModModelLayers.SURVIVOR_BODY,
                () -> chestLayer(false));
        event.registerLayerDefinition(ModModelLayers.SURVIVOR_BODY_SLIM,
                () -> chestLayer(true));
    }

    @Override
    public void setupAnim(T entity, float limbSwing, float limbSwingAmount, float ageInTicks,
                          float netHeadYaw, float headPitch) {
        // Выступ только у женского торса и только когда нагрудник не надет:
        // броня рисуется плоской ванильной геометрией и перекрыла бы выступ.
        boolean showChest = entity.getGender() == SurvivorGender.FEMALE
                && !(entity.getItemBySlot(EquipmentSlot.CHEST).getItem() instanceof ArmorItem);
        for (ModelPart segment : this.segments) {
            segment.visible = showChest;
        }
        for (ModelPart underside : this.undersides) {
            underside.visible = showChest;
        }
        super.setupAnim(entity, limbSwing, limbSwingAmount, ageInTicks, netHeadYaw, headPitch);
    }
}
