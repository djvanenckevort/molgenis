$(function () {

    $('#targetSampleCollectionName').select2();

    $('.table-row-clickable').find('tr').on('click', function () {
        var checkbox = $(this).find(':checkbox');
        checkbox.trigger('click');
    });

    $('input[type="checkbox"]').on('click', function () {
        var checkedboxes = $(this).parents('table:eq(0)').find(':checked');
        var sourceAttributeIdentifiers = [];
        $.map(checkedboxes, function (checkbox, i) {
            sourceAttributeIdentifiers.push($(checkbox).val());
        });
        var sourceAttributeIdentifiersHiddenInput = $(this).parents('.modal:eq(0)').find('input[name="sourceAttributes"]');
        sourceAttributeIdentifiersHiddenInput.val(sourceAttributeIdentifiers.join(','));
    })
});