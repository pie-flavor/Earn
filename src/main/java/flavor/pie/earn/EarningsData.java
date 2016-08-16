package flavor.pie.earn;

import com.google.common.collect.Maps;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.data.DataContainer;
import org.spongepowered.api.data.DataHolder;
import org.spongepowered.api.data.DataView;
import org.spongepowered.api.data.manipulator.DataManipulatorBuilder;
import org.spongepowered.api.data.manipulator.immutable.common.AbstractImmutableData;
import org.spongepowered.api.data.manipulator.mutable.common.AbstractData;
import org.spongepowered.api.data.merge.MergeFunction;
import org.spongepowered.api.data.persistence.AbstractDataBuilder;
import org.spongepowered.api.data.persistence.InvalidDataException;
import org.spongepowered.api.service.economy.Currency;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

public class EarningsData extends AbstractData<EarningsData, EarningsData.Immutable> {
    EarningsData() {
        registerGettersAndSetters();
        amounts = Maps.newHashMap();
        date = LocalDate.now();
    }

    Map<Currency, BigDecimal> amounts;
    LocalDate date;

    public Map<Currency, BigDecimal> getAmounts() {
        return amounts;
    }

    public void setAmounts(Map<Currency, BigDecimal> amounts) {
        this.amounts = amounts;
    }

    public LocalDate getDate() {
        return date;
    }

    public void setDate(LocalDate date) {
        this.date = date;
    }

    @Override
    protected void registerGettersAndSetters() {
        registerFieldGetter(Earn.AMOUNT_EARNED, this::getAmounts);
        registerFieldSetter(Earn.AMOUNT_EARNED, this::setAmounts);
        registerFieldGetter(Earn.EARNINGS_DATE, this::getDate);
        registerFieldSetter(Earn.EARNINGS_DATE, this::setDate);
    }

    @Override
    public Optional<EarningsData> fill(DataHolder dataHolder, MergeFunction overlap) {
        EarningsData data = overlap.merge(this, dataHolder.get(EarningsData.class).orElse(null));
        amounts = data.amounts;
        date = data.date;
        return Optional.of(this);
    }

    @Override
    public Optional<EarningsData> from(DataContainer container) {
        return from((DataView) container);
    }
    public Optional<EarningsData> from(DataView container) {
        Optional<? extends Map<?, ?>> map_ = container.getMap(Earn.AMOUNT_EARNED.getQuery());
        if (map_.isPresent()) {
            Map<Currency, BigDecimal> map = convertBackward((Map<String, Double>)map_.get());
            amounts = map;
        }
        Optional<Long> date_ = container.getLong(Earn.EARNINGS_DATE.getQuery());
        if (date_.isPresent()) {
            date = LocalDate.ofEpochDay(date_.get());
        }
        return Optional.of(this);
    }

    @Override
    public EarningsData copy() {
        EarningsData data = new EarningsData();
        data.amounts = Maps.newHashMap(amounts);
        data.date = date;
        return data;
    }

    @Override
    public Immutable asImmutable() {
        Immutable ret = new Immutable();
        ret.amounts = Maps.newHashMap(amounts);
        ret.date = date;
        return ret;
    }

    @Override
    public int compareTo(EarningsData o) {
        return date.compareTo(o.date);
    }

    @Override
    public int getContentVersion() {
        return 1;
    }

    static Map<String, Double> convertForward(Map<Currency, BigDecimal> in) {
        Map<String, Double> out = Maps.newHashMap();
        in.forEach((c, a) -> out.put(c.getId(), a.doubleValue()));
        return out;
    }

    static Map<Currency, BigDecimal> convertBackward(Map<String, Double> in) {
        Map<Currency, BigDecimal> out = Maps.newHashMap();
        in.forEach((c, a) -> out.put(Sponge.getRegistry().getType(Currency.class, c).get(), BigDecimal.valueOf(a)));
        return out;
    }

    public static class Immutable extends AbstractImmutableData<Immutable, EarningsData> {
        Map<Currency, BigDecimal> amounts;
        LocalDate date;
        public Immutable() {
            amounts = Maps.newHashMap();
            date = LocalDate.now();
        }
        @Override
        protected void registerGetters() {
            registerFieldGetter(Earn.EARNINGS_DATE, this::getDate);
            registerFieldGetter(Earn.AMOUNT_EARNED, this::getAmounts);
        }

        public Map<Currency, BigDecimal> getAmounts() {
            return amounts;
        }

        public LocalDate getDate() {
            return date;
        }

        @Override
        public EarningsData asMutable() {
            EarningsData ret = new EarningsData();
            ret.amounts = Maps.newHashMap(amounts);
            ret.date = date;
            return ret;
        }

        @Override
        public int compareTo(Immutable o) {
            return date.compareTo(o.date);
        }

        @Override
        public int getContentVersion() {
            return 1;
        }
    }

    public static class Builder extends AbstractDataBuilder<EarningsData> implements DataManipulatorBuilder<EarningsData, Immutable> {
        Builder() {
            super(EarningsData.class, 1);
        }
        @Override
        public EarningsData create() {
            return new EarningsData();
        }

        @Override
        public Optional<EarningsData> createFrom(DataHolder dataHolder) {
            return new EarningsData().fill(dataHolder);
        }

        @Override
        protected Optional<EarningsData> buildContent(DataView container) throws InvalidDataException {
            return new EarningsData().from(container);
        }
    }
}
