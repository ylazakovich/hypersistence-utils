package io.hypersistence.utils.hibernate.type.range.guava;

import com.google.common.collect.BoundType;
import com.google.common.collect.Range;
import io.hypersistence.utils.hibernate.type.ImmutableType;
import io.hypersistence.utils.common.ReflectionUtils;
import org.hibernate.HibernateException;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.models.internal.jdk.JdkFieldDetails;
import org.hibernate.usertype.DynamicParameterizedType;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.util.Properties;
import java.util.function.Function;

/**
 * Maps a {@link Range} object type to a PostgreSQL <a href="https://www.postgresql.org/docs/current/rangetypes.html">range</a>
 * column type.
 * <p>
 * Supported range types:
 * <ul>
 * <li>int4range</li>
 * <li>int8range</li>
 * <li>numrange</li>
 * <li>tsrange</li>
 * <li>tstzrange</li>
 * <li>daterange</li>
 * </ul>
 *
 * @author Edgar Asatryan
 * @author Vlad Mihalcea
 * @author Jan-Willem Gmelig Meyling
 */
public class PostgreSQLGuavaRangeType extends ImmutableType<Range> implements DynamicParameterizedType {

    /**
     * An empty int range that satisfies {@link Range#isEmpty()} to map PostgreSQL's {@code empty} to.
     */
    private static final Range<Integer> EMPTY_INT_RANGE = Range.closedOpen(Integer.MIN_VALUE, Integer.MIN_VALUE);

    /**
     * An empty int range that satisfies {@link Range#isEmpty()} to map PostgreSQL's {@code empty} to.
     */
    private static final Range<Long> EMPTY_LONG_RANGE = Range.closedOpen(Long.MIN_VALUE, Long.MIN_VALUE);

    /**
     * An empty int range that satisfies {@link Range#isEmpty()} to map PostgreSQL's {@code empty} to.
     */
    private static final Range<BigDecimal> EMPTY_BIGDECIMAL_RANGE = Range.closedOpen(BigDecimal.ZERO, BigDecimal.ZERO);

    /**
     * An empty int range that satisfies {@link Range#isEmpty()} to map PostgreSQL's {@code empty} to.
     */
    private static final Range<LocalDateTime> EMPTY_LOCALDATETIME_RANGE = Range.closedOpen(LocalDateTime.MIN, LocalDateTime.MIN);

    /**
     * An empty int range that satisfies {@link Range#isEmpty()} to map PostgreSQL's {@code empty} to.
     */
    private static final Range<OffsetDateTime> EMPTY_OFFSETDATETIME_RANGE = Range.closedOpen(OffsetDateTime.MIN, OffsetDateTime.MIN);

    /**
     * An empty int range that satisfies {@link Range#isEmpty()} to map PostgreSQL's {@code empty} to.
     */
    private static final Range<ZonedDateTime> EMPTY_ZONEDDATETIME_RANGE = Range.closedOpen(OffsetDateTime.MIN.toZonedDateTime(), OffsetDateTime.MIN.toZonedDateTime());

    /**
     * An empty int range that satisfies {@link Range#isEmpty()} to map PostgreSQL's {@code empty} to.
     */
    private static final Range<LocalDate> EMPTY_DATE_RANGE = Range.closedOpen(LocalDate.MIN, LocalDate.MIN);

    private static final DateTimeFormatter LOCAL_DATE_TIME = new DateTimeFormatterBuilder()
        .appendPattern("yyyy-MM-dd HH:mm:ss")
        .optionalStart()
        .appendPattern(".")
        .appendFraction(ChronoField.NANO_OF_SECOND, 1, 6, false)
        .optionalEnd()
        .toFormatter();

    private static final DateTimeFormatter OFFSET_DATE_TIME = new DateTimeFormatterBuilder()
            .appendPattern("yyyy-MM-dd HH:mm:ss")
            .optionalStart()
            .appendPattern(".")
            .appendFraction(ChronoField.NANO_OF_SECOND, 1, 6, false)
            .optionalEnd()
            .appendPattern("X")
            .toFormatter();

    public static final PostgreSQLGuavaRangeType INSTANCE = new PostgreSQLGuavaRangeType();

    private Type type;

    private Class<?> elementType;

    public PostgreSQLGuavaRangeType() {
        super(Range.class);
    }

    public PostgreSQLGuavaRangeType(Class<?> elementType) {
        super(Range.class);
        this.elementType = elementType;
    }

    @Override
    public int getSqlType() {
        return Types.OTHER;
    }

    @Override
    protected Range get(ResultSet rs, int position, SharedSessionContractImplementor session, Object owner) throws SQLException {
        Object pgObject = rs.getObject(position);

        if (pgObject == null) {
            return null;
        }

        String type = ReflectionUtils.invokeGetter(pgObject, "type");
        String value = ReflectionUtils.invokeGetter(pgObject, "value");

        switch (type) {
            case "int4range":
                return integerRange(value);
            case "int8range":
                return longRange(value);
            case "numrange":
                return bigDecimalRange(value);
            case "tsrange":
                return localDateTimeRange(value);
            case "tstzrange":
                return ZonedDateTime.class.equals(elementType) ? zonedDateTimeRange(value) : offsetDateTimeRange(value);
            case "daterange":
                return localDateRange(value);
            default:
                throw new HibernateException(
                    new IllegalStateException("The range type [" + type + "] is not supported!")
                );
        }
    }

    @Override
    protected void set(PreparedStatement st, Range range, int index, SharedSessionContractImplementor session) throws SQLException {
        if (range == null) {
            st.setNull(index, Types.OTHER);
        } else {
            Object holder = ReflectionUtils.newInstance("org.postgresql.util.PGobject");
            ReflectionUtils.invokeSetter(holder, "type", determineRangeType(range));
            ReflectionUtils.invokeSetter(holder, "value", asString(range));
            st.setObject(index, holder);
        }
    }

    private String determineRangeType(Range<?> range) {
        Type clazz = this.elementType;

        if (clazz == null) {
            Object anyEndpoint = range.hasLowerBound() ? range.lowerEndpoint() :
                                 range.hasUpperBound() ? range.upperEndpoint() : null;

            if (anyEndpoint == null) {
                throw new HibernateException(
                    new IllegalArgumentException("The range " + range + " doesn't have any upper or lower bound!")
                );
            }

            clazz = anyEndpoint.getClass();
        }

        if (clazz.equals(Integer.class)) {
            return "int4range";
        } else if (clazz.equals(Long.class)) {
            return "int8range";
        } else if (clazz.equals(BigDecimal.class)) {
            return "numrange";
        } else if (clazz.equals(LocalDateTime.class)) {
            return "tsrange";
        } else if (clazz.equals(ZonedDateTime.class) || clazz.equals(OffsetDateTime.class)) {
            return "tstzrange";
        } else if (clazz.equals(LocalDate.class)) {
            return "daterange";
        }

        throw new HibernateException(
            new IllegalStateException("The class [" + clazz + "] is not supported!")
        );
    }

    public static <T extends Comparable<?>> Range<T> ofString(String str, Function<String, T> converter, Class<T> clazz) {
        if ("empty".equals(str)) {
            if (clazz.equals(Integer.class)) {
                return (Range<T>) EMPTY_INT_RANGE;
            } else if (clazz.equals(Long.class)) {
                return (Range<T>) EMPTY_LONG_RANGE;
            } else if (clazz.equals(BigDecimal.class)) {
                return (Range<T>) EMPTY_BIGDECIMAL_RANGE;
            } else if (clazz.equals(LocalDateTime.class)) {
                return (Range<T>) EMPTY_LOCALDATETIME_RANGE;
            } else if (clazz.equals(ZonedDateTime.class)) {
                return (Range<T>) EMPTY_ZONEDDATETIME_RANGE;
            } else if (clazz.equals(OffsetDateTime.class)) {
                return (Range<T>) EMPTY_OFFSETDATETIME_RANGE;
            } else if (clazz.equals(LocalDate.class)) {
                return (Range<T>) EMPTY_DATE_RANGE;
            }

            throw new HibernateException(
                    new IllegalStateException("The class [" + clazz.getName() + "] is not supported!")
            );
        }

        BoundType lowerBound = str.charAt(0) == '[' ? BoundType.CLOSED : BoundType.OPEN;
        BoundType upperBound = str.charAt(str.length() - 1) == ']' ? BoundType.CLOSED : BoundType.OPEN;

        int delim = str.indexOf(',');

        if (delim == -1) {
            throw new HibernateException(
                new IllegalArgumentException("Cannot find comma character")
            );
        }

        String lowerStr = str.substring(1, delim);
        String upperStr = str.substring(delim + 1, str.length() - 1);

        T lower = null;
        T upper = null;

        if (lowerStr.length() > 0) {
            lower = converter.apply(lowerStr);
        }

        if (upperStr.length() > 0) {
            upper = converter.apply(upperStr);
        }

        if (lower == null && upper == null && upperBound == BoundType.OPEN && lowerBound == BoundType.OPEN) {
            return Range.all();
        }

        if (lowerStr.length() == 0) {
            return upperBound == BoundType.CLOSED ?
                    Range.atMost(upper) :
                    Range.lessThan(upper);
        } else if (upperStr.length() == 0) {
            return lowerBound == BoundType.CLOSED ?
                    Range.atLeast(lower) :
                    Range.greaterThan(lower);
        } else {
            return Range.range(lower, lowerBound, upper, upperBound);
        }
    }

    /**
     * Creates the {@code BigDecimal} range from provided string:
     * <pre>{@code
     *     Range<BigDecimal> closed = Range.bigDecimalRange("[0.1,1.1]");
     *     Range<BigDecimal> halfOpen = Range.bigDecimalRange("(0.1,1.1]");
     *     Range<BigDecimal> open = Range.bigDecimalRange("(0.1,1.1)");
     *     Range<BigDecimal> leftUnbounded = Range.bigDecimalRange("(,1.1)");
     * }</pre>
     *
     * @param range The range string, for example {@literal "[5.5,7.8]"}.
     *
     * @return The range of {@code BigDecimal}s.
     *
     * @throws NumberFormatException when one of the bounds are invalid.
     */
    public static Range<BigDecimal> bigDecimalRange(String range) {
        return ofString(range, BigDecimal::new, BigDecimal.class);
    }

    /**
     * Creates the {@code Integer} range from provided string:
     * <pre>{@code
     *     Range<Integer> closed = Range.integerRange("[1,5]");
     *     Range<Integer> halfOpen = Range.integerRange("(-1,1]");
     *     Range<Integer> open = Range.integerRange("(1,2)");
     *     Range<Integer> leftUnbounded = Range.integerRange("(,10)");
     *     Range<Integer> unbounded = Range.integerRange("(,)");
     * }</pre>
     *
     * @param range The range string, for example {@literal "[5,7]"}.
     *
     * @return The range of {@code Integer}s.
     *
     * @throws NumberFormatException when one of the bounds are invalid.
     */
    public static Range<Integer> integerRange(String range) {
        return ofString(range, Integer::parseInt, Integer.class);
    }

    /**
     * Creates the {@code Long} range from provided string:
     * <pre>{@code
     *     Range<Long> closed = Range.longRange("[1,5]");
     *     Range<Long> halfOpen = Range.longRange("(-1,1]");
     *     Range<Long> open = Range.longRange("(1,2)");
     *     Range<Long> leftUnbounded = Range.longRange("(,10)");
     *     Range<Long> unbounded = Range.longRange("(,)");
     * }</pre>
     *
     * @param range The range string, for example {@literal "[5,7]"}.
     *
     * @return The range of {@code Long}s.
     *
     * @throws NumberFormatException when one of the bounds are invalid.
     */
    public static Range<Long> longRange(String range) {
        return ofString(range, Long::parseLong, Long.class);
    }

    /**
     * Creates the {@code LocalDateTime} range from provided string:
     * <pre>{@code
     *     Range<LocalDateTime> closed = Range.localDateTimeRange("[2014-04-28 16:00:49,2015-04-28 16:00:49]");
     *     Range<LocalDateTime> quoted = Range.localDateTimeRange("[\"2014-04-28 16:00:49\",\"2015-04-28 16:00:49\"]");
     *     Range<LocalDateTime> iso = Range.localDateTimeRange("[\"2014-04-28T16:00:49.2358\",\"2015-04-28T16:00:49\"]");
     * }</pre>
     * <p>
     * The valid formats for bounds are:
     * <ul>
     * <li>yyyy-MM-dd HH:mm:ss[.SSSSSS]</li>
     * <li>yyyy-MM-dd'T'HH:mm:ss[.SSSSSS]</li>
     * </ul>
     *
     * @param range The range string, for example {@literal "[2014-04-28 16:00:49,2015-04-28 16:00:49]"}.
     *
     * @return The range of {@code LocalDateTime}s.
     *
     * @throws DateTimeParseException when one of the bounds are invalid.
     */
    public static Range<LocalDateTime> localDateTimeRange(String range) {
        return ofString(range, parseLocalDateTime().compose(unquote()), LocalDateTime.class);
    }

    /**
     * Creates the {@code LocalDate} range from provided string:
     * <pre>{@code
     *     Range<LocalDate> closed = Range.localDateRange("[2014-04-28,2015-04-289]");
     *     Range<LocalDate> quoted = Range.localDateRange("[\"2014-04-28\",\"2015-04-28\"]");
     *     Range<LocalDate> iso = Range.localDateRange("[\"2014-04-28\",\"2015-04-28\"]");
     * }</pre>
     * <p>
     * The valid formats for bounds are:
     * <ul>
     * <li>yyyy-MM-dd</li>
     * <li>yyyy-MM-dd</li>
     * </ul>
     *
     * @param range The range string, for example {@literal "[2014-04-28,2015-04-28]"}.
     *
     * @return The range of {@code LocalDate}s.
     *
     * @throws DateTimeParseException when one of the bounds are invalid.
     */
    public static Range<LocalDate> localDateRange(String range) {
        Function<String, LocalDate> parseLocalDate = LocalDate::parse;
        return ofString(range, parseLocalDate.compose(unquote()), LocalDate.class);
    }

    /**
     * Creates the {@code ZonedDateTime} range from provided string:
     * <pre>{@code
     *     Range<ZonedDateTime> closed = Range.zonedDateTimeRange("[2007-12-03T10:15:30+01:00\",\"2008-12-03T10:15:30+01:00]");
     *     Range<ZonedDateTime> quoted = Range.zonedDateTimeRange("[\"2007-12-03T10:15:30+01:00\",\"2008-12-03T10:15:30+01:00\"]");
     *     Range<ZonedDateTime> iso = Range.zonedDateTimeRange("[2011-12-03T10:15:30+01:00[Europe/Paris], 2012-12-03T10:15:30+01:00[Europe/Paris]]");
     * }</pre>
     * <p>
     * The valid formats for bounds are:
     * <ul>
     * <li>yyyy-MM-dd HH:mm:ss[.SSSSSS]X</li>
     * <li>yyyy-MM-dd'T'HH:mm:ss[.SSSSSS]X</li>
     * </ul>
     *
     * @param rangeStr The range string, for example {@literal "[2011-12-03T10:15:30+01:00,2012-12-03T10:15:30+01:00]"}.
     *
     * @return The range of {@code ZonedDateTime}s.
     *
     * @throws DateTimeParseException   when one of the bounds are invalid.
     * @throws IllegalArgumentException when bounds time zones are different.
     */
    public static Range<ZonedDateTime> zonedDateTimeRange(String rangeStr) {
        Range<ZonedDateTime> range = ofString(rangeStr, parseZonedDateTime().compose(unquote()), ZonedDateTime.class);
        if (range.hasLowerBound() && range.hasUpperBound()) {
            ZoneId lowerZone = range.lowerEndpoint().getZone();
            ZoneId upperZone = range.upperEndpoint().getZone();
            if (!lowerZone.equals(upperZone)) {
                Duration lowerDst = ZoneId.systemDefault().getRules().getDaylightSavings(range.lowerEndpoint().toInstant());
                Duration upperDst = ZoneId.systemDefault().getRules().getDaylightSavings(range.upperEndpoint().toInstant());
                long dstSeconds = upperDst.minus(lowerDst).getSeconds();
                if (dstSeconds < 0) {
                    dstSeconds *= -1;
                }
                long zoneDriftSeconds = ((ZoneOffset) lowerZone).getTotalSeconds() - ((ZoneOffset) upperZone).getTotalSeconds();
                if (zoneDriftSeconds < 0) {
                    zoneDriftSeconds *= -1;
                }

                if (dstSeconds != zoneDriftSeconds) {
                    throw new HibernateException(
                        new IllegalArgumentException("The upper and lower bounds must be in same time zone!")
                    );
                }
            }
        }
        return range;
    }

    /**
     * Creates the {@code OffsetDateTime} range from provided string:
     * <pre>{@code
     *     Range<OffsetDateTime> closed = Range.offsetDateTimeRange("[2007-12-03T10:15:30+01:00\",\"2008-12-03T10:15:30+01:00]");
     *     Range<OffsetDateTime> quoted = Range.offsetDateTimeRange("[\"2007-12-03T10:15:30+01:00\",\"2008-12-03T10:15:30+01:00\"]");
     *     Range<OffsetDateTime> iso = Range.offsetDateTimeRange("[2011-12-03T10:15:30+01:00[Europe/Paris], 2012-12-03T10:15:30+01:00[Europe/Paris]]");
     * }</pre>
     * <p>
     * The valid formats for bounds are:
     * <ul>
     * <li>yyyy-MM-dd HH:mm:ss[.SSSSSS]X</li>
     * <li>yyyy-MM-dd'T'HH:mm:ss[.SSSSSS]X</li>
     * </ul>
     *
     * @param rangeStr The range string, for example {@literal "[2011-12-03T10:15:30+01:00,2012-12-03T10:15:30+01:00]"}.
     *
     * @return The range of {@code OffsetDateTime}s.
     *
     * @throws DateTimeParseException   when one of the bounds are invalid.
     * @throws IllegalArgumentException when bounds time zones are different.
     */
    public static Range<OffsetDateTime> offsetDateTimeRange(String rangeStr) {
        return ofString(rangeStr, parseOffsetDateTime().compose(unquote()), OffsetDateTime.class);
    }

    private static Function<String, LocalDateTime> parseLocalDateTime() {
        return str -> {
            try {
                return LocalDateTime.parse(str, LOCAL_DATE_TIME);
            } catch (DateTimeParseException e) {
                return LocalDateTime.parse(str);
            }
        };
    }

    private static Function<String, ZonedDateTime> parseZonedDateTime() {
        return s -> {
            try {
                return ZonedDateTime.parse(s, OFFSET_DATE_TIME);
            } catch (DateTimeParseException e) {
                return ZonedDateTime.parse(s);
            }
        };
    }

    private static Function<String, OffsetDateTime> parseOffsetDateTime() {
        return s -> {
            try {
                return OffsetDateTime.parse(s, OFFSET_DATE_TIME);
            } catch (DateTimeParseException e) {
                return OffsetDateTime.parse(s);
            }
        };
    }

    private static Function<String, String> unquote() {
        return s -> {
            if (s.charAt(0) == '\"' && s.charAt(s.length() - 1) == '\"') {
                return s.substring(1, s.length() - 1);
            }

            return s;
        };
    }

    public String asString(Range range) {
        if (range.isEmpty()) {
            return "empty";
        }

        StringBuilder sb = new StringBuilder();

        sb.append(range.hasLowerBound() && range.lowerBoundType() == BoundType.CLOSED ? '[' : '(')
                .append(range.hasLowerBound() ? asString(range.lowerEndpoint()) : "")
                .append(",")
                .append(range.hasUpperBound() ? asString(range.upperEndpoint()) : "")
                .append(range.hasUpperBound() && range.upperBoundType() == BoundType.CLOSED ? ']' : ')');

        return sb.toString();
    }

    private String asString(Object value) {
        if (value instanceof ZonedDateTime) {
            return OFFSET_DATE_TIME.format((ZonedDateTime) value);
        }
        return value.toString();
    }

    @Override
    public void setParameterValues(Properties parameters) {
        final ParameterType parameterType = (ParameterType) parameters.get(PARAMETER_TYPE);

        type = parameterType.getReturnedClass();

        final Type returnedJavaType = parameterType.getReturnedJavaType();
        if (returnedJavaType instanceof ParameterizedType) {
            elementType = (Class<?>) ((ParameterizedType) returnedJavaType).getActualTypeArguments()[0];
        }
    }

    public Class<?> getElementType() {
        return elementType;
    }

    @Override
    public Range fromStringValue(CharSequence sequence) throws HibernateException {
        if (sequence != null) {
            String stringValue = (String) sequence;
            Class clazz = rangeClass();
            if(clazz != null) {
                if(Integer.class.isAssignableFrom(clazz)) {
                    return integerRange(stringValue);
                }
                if(Long.class.isAssignableFrom(clazz)) {
                    return longRange(stringValue);
                }
                if(BigDecimal.class.isAssignableFrom(clazz)) {
                    return bigDecimalRange(stringValue);
                }
                if(LocalDateTime.class.isAssignableFrom(clazz)) {
                    return localDateTimeRange(stringValue);
                }
                if(ZonedDateTime.class.isAssignableFrom(clazz)) {
                    return zonedDateTimeRange(stringValue);
                }
                if(LocalDate.class.isAssignableFrom(clazz)) {
                    return localDateRange(stringValue);
                }
                throw new HibernateException(
                    new IllegalStateException("The range type [" + type + "] is not supported!")
                );
            }
        }
        return null;
    }

    private Class rangeClass() {
        if (type instanceof ParameterizedType) {
            Type[] types = ((ParameterizedType) type).getActualTypeArguments();
            return (Class) types[0];
        }
        return null;
    }
}
