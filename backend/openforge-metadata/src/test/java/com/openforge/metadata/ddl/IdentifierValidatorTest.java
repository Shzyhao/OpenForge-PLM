package com.openforge.metadata.ddl;

import com.openforge.common.api.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 标识符白名单校验（F2 设计 4 安全红线）：正则、保留字、标准列三道闸。 */
class IdentifierValidatorTest {

    @Test
    @DisplayName("合法标识符通过：小写开头 snake_case，3~41 位")
    void validKeysPass() {
        assertThatCode(() -> IdentifierValidator.checkObjectKey("equipment")).doesNotThrowAnyException();
        assertThatCode(() -> IdentifierValidator.checkObjectKey("part_master_data")).doesNotThrowAnyException();
        assertThatCode(() -> IdentifierValidator.checkFieldKey("abc")).doesNotThrowAnyException();
        assertThatCode(() -> IdentifierValidator.checkFieldKey("purchase_price_2")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("正则红线：大写/数字开头/过短/过长/特殊字符一律拒绝")
    void regexViolationsRejected() {
        String[] bad = {"Equipment", "1part", "ab", "x", "a".repeat(42), "part-key", "part key",
                "part;DROP TABLE part", "name--comment", "name/**/collist", "part\nequipment", "part'", "\"part\"",
                "part(", "名称"};
        for (String key : bad) {
            assertThatThrownBy(() -> IdentifierValidator.checkObjectKey(key))
                    .as("objectKey 应被拒绝: %s", key)
                    .isInstanceOf(BizException.class)
                    .satisfies(e -> assertThat(((BizException) e).getErrorCode().getCode()).isEqualTo(1000));
        }
    }

    @Test
    @DisplayName("SQL 保留字黑名单：select/table/user/drop 等拒绝（宁误杀不漏放）")
    void reservedWordsRejected() {
        String[] reserved = {"select", "table", "user", "order", "group", "drop", "insert", "update",
                "delete", "index", "grant", "default", "primary", "references", "case", "when", "end"};
        for (String key : reserved) {
            assertThatThrownBy(() -> IdentifierValidator.checkFieldKey(key))
                    .as("保留字应被拒绝: %s", key)
                    .isInstanceOf(BizException.class);
        }
    }

    @Test
    @DisplayName("fieldKey 不得与动态表标准列重名")
    void standardColumnsRejectedForFieldKey() {
        for (String column : IdentifierValidator.STANDARD_COLUMNS) {
            assertThatThrownBy(() -> IdentifierValidator.checkFieldKey(column))
                    .as("标准列应被拒绝: %s", column)
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("标准列");
        }
        // objectKey 不受标准列限制（表名带 dyn_ 前缀，不会与标准列冲突）
        assertThatCode(() -> IdentifierValidator.checkObjectKey("deleted")).doesNotThrowAnyException();
    }
}
