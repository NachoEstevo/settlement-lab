package lab.settlement.verification;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;

import java.io.IOException;
import java.math.BigInteger;
import java.util.regex.Pattern;

/** Version 1 writes monetary values as canonical unsigned ASCII decimal strings. */
abstract class MoneyDeserializer<T> extends JsonDeserializer<T> {
    private static final Pattern DECIMAL = Pattern.compile("0|[1-9][0-9]*");
    private static final String EXPECTED = "Expected a canonical nonnegative decimal string for money";

    @Override
    public final T deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        if (!parser.hasToken(JsonToken.VALUE_STRING) || !DECIMAL.matcher(parser.getText()).matches()) {
            return context.reportInputMismatch(Number.class, EXPECTED);
        }
        try {
            return parse(parser.getText());
        } catch (NumberFormatException outOfRange) {
            return context.reportInputMismatch(Number.class, "Amount exceeds the supported integer range");
        }
    }

    @Override
    public final T getNullValue(DeserializationContext context) throws JsonMappingException {
        return context.reportInputMismatch(Number.class, EXPECTED);
    }

    abstract T parse(String decimal);

    static final class LongUnits extends MoneyDeserializer<Long> {
        @Override Long parse(String decimal) { return Long.parseLong(decimal); }
    }

    static final class GrossUnits extends MoneyDeserializer<BigInteger> {
        @Override BigInteger parse(String decimal) { return new BigInteger(decimal); }
    }
}
