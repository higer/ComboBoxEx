package org.eclipse.swt.widgets;

import org.eclipse.swt.graphics.Image;

public class ComboItem extends Item
{

    public ComboItem(ComboBoxEx parent, String text, Image image, int style)
    {
        super(parent, style);

        parent.add(text, image);
    }

    public ComboItem(ComboBoxEx parent, String text, Image image, int index, int style)
    {
        super(parent, style, index);

        parent.add(text, image, index);
    }

}
