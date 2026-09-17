package com.hwannee.ieum.orders.stock;

import com.hwannee.ieum.orders.exception.OrderException;
import com.hwannee.ieum.stores.domain.StoreType;
import com.hwannee.ieum.stores.domain.Stores;
import com.hwannee.ieum.stores.domain.StoresItems;
import com.hwannee.ieum.stores.repository.StoresItemsRepository;
import com.hwannee.ieum.users.domain.UserType;
import com.hwannee.ieum.users.domain.UsersAccount;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class ConditionalUpdateStockDeductionTest {

    private static final long ITEM_ID = 10L;

    @Mock
    StoresItemsRepository items;

    @InjectMocks
    ConditionalUpdateStockDeduction strategy;

    @Test
    void 재고가_있으면_조건부_UPDATE_한_번으로_끝나고_엔티티는_건드리지_않는다() {
        StoresItems item = item(5);
        given(items.findById(ITEM_ID)).willReturn(Optional.of(item));
        given(items.deductIfAvailable(ITEM_ID, 1)).willReturn(1);

        assertThatCode(() -> strategy.deduct(ITEM_ID, 1)).doesNotThrowAnyException();

        then(items).should().deductIfAvailable(ITEM_ID, 1);
        assertThat(item.getRemainingQuantity()).isEqualTo(5);
        assertThat(item.getVersion()).isNull();
    }

    @Test
    void 스냅샷_재고가_부족하면_UPDATE_없이_InsufficientStock() {
        given(items.findById(ITEM_ID)).willReturn(Optional.of(item(0)));

        assertThatThrownBy(() -> strategy.deduct(ITEM_ID, 1))
                .isInstanceOf(OrderException.InsufficientStock.class)
                .hasMessageContaining("0");

        then(items).should(never()).deductIfAvailable(anyLong(), anyInt());
    }

    @Test
    void 스냅샷은_충분한데_UPDATE_가_0건이면_InsufficientStock() {
        given(items.findById(ITEM_ID)).willReturn(Optional.of(item(1)));
        given(items.deductIfAvailable(ITEM_ID, 1)).willReturn(0);

        assertThatThrownBy(() -> strategy.deduct(ITEM_ID, 1))
                .isInstanceOf(OrderException.InsufficientStock.class)
                .hasMessage("재고가 부족합니다.");
    }

    @Test
    void 상품이_없으면_ItemNotFound() {
        given(items.findById(ITEM_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> strategy.deduct(ITEM_ID, 1))
                .isInstanceOf(OrderException.ItemNotFound.class);

        then(items).should(never()).deductIfAvailable(anyLong(), anyInt());
    }

    @Test
    void 복구가_초기_수량_안이면_예외_없이_끝난다() {
        given(items.restoreIfWithinInitial(ITEM_ID, 1)).willReturn(1);

        assertThatCode(() -> strategy.restore(ITEM_ID, 1)).doesNotThrowAnyException();
    }

    @Test
    void 복구가_초기_수량을_넘기면_IllegalStateException() {
        given(items.restoreIfWithinInitial(ITEM_ID, 1)).willReturn(0);

        assertThatThrownBy(() -> strategy.restore(ITEM_ID, 1))
                .isInstanceOf(IllegalStateException.class);
    }

    private StoresItems item(int remaining) {
        UsersAccount owner = new UsersAccount(null, UserType.BUSINESS_OWNER, "owner-uid", "owner", "encoded");
        Stores store = new Stores(owner, "store-uid", "가게", StoreType.CAFE, LocalTime.of(9, 0), LocalTime.of(18, 0));
        StoresItems item = new StoresItems(store, "item-uid", null, "빵", 5000, 3000, 100, LocalDateTime.now().plusHours(1));
        ReflectionTestUtils.setField(item, "id", ITEM_ID);
        ReflectionTestUtils.setField(item, "remainingQuantity", remaining);
        return item;
    }
}
