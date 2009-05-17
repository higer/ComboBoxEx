/*******************************************************************************
 * Copyright (c) 2000, 2009 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation of Combo class
 *     Lukasz MIlewski - implementation of ComboBoxEx class
 *******************************************************************************/
package org.eclipse.swt.widgets;

import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTException;
import org.eclipse.swt.events.ModifyListener;
import org.eclipse.swt.events.SelectionListener;
import org.eclipse.swt.events.VerifyListener;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.internal.Callback;
import org.eclipse.swt.internal.ImageList;
import org.eclipse.swt.internal.win32.COMBOBOXINFO;
import org.eclipse.swt.internal.win32.LRESULT;
import org.eclipse.swt.internal.win32.MONITORINFO;
import org.eclipse.swt.internal.win32.MSG;
import org.eclipse.swt.internal.win32.OS;
import org.eclipse.swt.internal.win32.RECT;
import org.eclipse.swt.internal.win32.TCHAR;
import org.eclipse.swt.internal.win32.WINDOWPOS;
import org.eclipse.swt.internal.win32.WNDCLASS;
import com.sun.jna.Pointer;
import com.sun.jna.win32.libraries.ComCtl32;
import com.sun.jna.win32.libraries.User32;
import com.sun.jna.win32.libraries.W32API;
import com.sun.jna.win32.libraries.W32API.HWND;

public class ComboBoxEx extends Composite
{

    boolean noSelection;
    boolean ignoreDefaultSelection;
    boolean ignoreCharacter;
    boolean ignoreModify;
    boolean ignoreResize;

    private ImageList imageList;

    int scrollWidth;
    int visibleCount = 5;

    int cbtHook;

    /**
     * the operating system limit for the number of characters that the text
     * field in an instance of this class can hold
     */
    public static final int LIMIT;

    /*
     * These values can be different on different platforms. Therefore they are
     * not initialized in the declaration to stop the compiler from inlining.
     */
    static
    {
        LIMIT = OS.IsWinNT ? 0x7FFFFFFF : 0x7FFF;
    }

    static int EditProc, ListProc;

    static final int ComboBoxProc;
    static final TCHAR ComboBoxExClass = new TCHAR(0, "COMBOBOXEX32", true);

    public static final int CBEM_SETIMAGELIST = 0x402;
    public static final int CBEM_GETIMAGELIST = 0x403;
    public static final int CBEM_GETCOMBOCONTROL = 0x406;
    public static final int CBEM_GETEDITCONTROL = 0x407;
    public static final int CBEM_INSERTITEM = 0x40b;

    public static final int CBEIF_TEXT = 0x1;
    public static final int CBEIF_IMAGE = 0x2;
    public static final int CBEIF_SELECTEDIMAGE = 0x4;
    public static final int CBEIF_INDENT = 0x10;

    static
    {
        WNDCLASS lpWndClass = new WNDCLASS();
        OS.GetClassInfo(0, ComboBoxExClass, lpWndClass);
        ComboBoxProc = lpWndClass.lpfnWndProc;
    }

    /**
     * Constructs a new instance of this class given its parent and a style
     * value describing its behavior and appearance.
     * <p>
     * The style value is either one of the style constants defined in class
     * <code>SWT</code> which is applicable to instances of this class, or must
     * be built by <em>bitwise OR</em>'ing together (that is, using the
     * <code>int</code> "|" operator) two or more of those <code>SWT</code>
     * style constants. The class description lists the style constants that are
     * applicable to the class. Style bits are also inherited from superclasses.
     * </p>
     * 
     * @param parent a composite control which will be the parent of the new
     *            instance (cannot be null)
     * @param style the style of control to construct
     */
    public ComboBoxEx(Composite parent, int style)
    {
        super(parent, checkStyle(style));

        this.style = checkStyle(style);

        // Difference between ComboBox and ComboBoxEx32 is that you
        // can't set H_SCROLL because it will draw some artifacts
        // around edit box (observed on Vista).
        if ((this.style & SWT.H_SCROLL) != 0)
        {
            this.style |= ~SWT.H_SCROLL;
        }
    }

    /**
     * Adds the argument to the end of the receiver's list.
     * 
     * @param string the new item
     * @param image the new image
     */
    void add(String string, Image image)
    {
        add(string, image, -1);
    }

    /**
     * Adds the argument to the receiver's list at the given zero-relative
     * index.
     * <p>
     * Note: To add an item at the end of the list, use the -1 value as the
     * index or use <code>add(String)</code>.
     * </p>
     * 
     * @param string the new item
     * @param image the new Image
     * @param index the index for the item
     */
    void add(String string, Image image, int index)
    {
        checkWidget();

        if (string == null)
        {
            error(SWT.ERROR_NULL_ARGUMENT);
        }

        int count = (int) OS.SendMessage(handle, OS.CB_GETCOUNT, 0, 0);

        if (!(index <= count))
        {
            error(SWT.ERROR_INVALID_RANGE);
        }

        int imageIndex = addImage(image);

        /*
         * Following parts of the code uses JNA (Java Native Access)
         * to access native win32 code, reason is that COMBOBOXEXITEM
         * is not available in SWT and SendMessage with COMBOBOXEXITEM
         * as a LPARAM is not available in JNI dll.
         * 
         * Introducing another SendMessage method and COMBOBOXEXITEM
         * structure into SWT will reduce the dependency on JNA.
         */

        ComCtl32.COMBOBOXEXITEM item = new ComCtl32.COMBOBOXEXITEM();
        item.mask = CBEIF_TEXT | CBEIF_IMAGE | CBEIF_INDENT | CBEIF_SELECTEDIMAGE;
        item.pszText = string;
        item.iItem = index;
        item.iImage = imageIndex;
        item.iSelectedImage = imageIndex;
        item.cchTextMax = string.length();

        HWND hwnd = new HWND();
        hwnd.setPointer(Pointer.createConstant(handle));

        User32.INSTANCE.SendMessage(hwnd, CBEM_INSERTITEM, new W32API.WPARAM(), item);
    }

    /**
     * Adds image to ComboBoxEx ImageList.
     * 
     * @param image Image
     * @return index in ImageList
     */
    int addImage(Image image)
    {
        if (imageList == null)
        {
            imageList = new ImageList (style & SWT.RIGHT_TO_LEFT);
        }

        int returnValue = imageList.add(image);

        OS.SendMessage(handle, CBEM_SETIMAGELIST, 0, imageList.getHandle());

        return returnValue;
    }

    /**
     * Adds the listener to the collection of listeners who will be notified
     * when the receiver's text is modified, by sending it one of the messages
     * defined in the <code>ModifyListener</code> interface.
     * 
     * @param listener the listener which should be notified
     */
    public void addModifyListener(ModifyListener listener)
    {
        checkWidget();

        if (listener == null)
        {
            error(SWT.ERROR_NULL_ARGUMENT);
        }

        TypedListener typedListener = new TypedListener(listener);
        addListener(SWT.Modify, typedListener);
    }

    /**
     * Adds the listener to the collection of listeners who will be notified
     * when the user changes the receiver's selection, by sending it one of the
     * messages defined in the <code>SelectionListener</code> interface.
     * <p>
     * <code>widgetSelected</code> is called when the user changes the combo's
     * list selection. <code>widgetDefaultSelected</code> is typically called
     * when ENTER is pressed the combo's text area.
     * </p>
     * 
     * @param listener the listener which should be notified
     */
    public void addSelectionListener(SelectionListener listener)
    {
        checkWidget();

        if (listener == null)
        {
            error(SWT.ERROR_NULL_ARGUMENT);
        }

        TypedListener typedListener = new TypedListener(listener);
        addListener(SWT.Selection, typedListener);
        addListener(SWT.DefaultSelection, typedListener);
    }

    /**
     * Adds the listener to the collection of listeners who will be notified
     * when the receiver's text is verified, by sending it one of the messages
     * defined in the <code>VerifyListener</code> interface.
     * 
     * @param listener the listener which should be notified
     */
    public void addVerifyListener(VerifyListener listener)
    {
        checkWidget();

        if (listener == null)
        {
            error(SWT.ERROR_NULL_ARGUMENT);
        }

        TypedListener typedListener = new TypedListener(listener);
        addListener(SWT.Verify, typedListener);
    }

    /**
     * Delegates messages from main widget to specified handler.
     * 
     * @param hwnd Handler
     * @param msg message
     * @param wParam WPARAM
     * @param lParam LPARAM
     */
    int callWindowProc(int hwnd, int msg, int wParam, int lParam)
    {
        if (handle == 0)
        {
            return 0;
        }

        if (hwnd == handle)
        {
            switch (msg)
            {
                case OS.WM_SIZE:
                {
                    ignoreResize = true;
                    int result = OS.CallWindowProc(ComboBoxProc, hwnd, msg, wParam, lParam);
                    ignoreResize = false;

                    return result;
                }
            }

            return OS.CallWindowProc(ComboBoxProc, hwnd, msg, wParam, lParam);
        }

        int hwndText = getEditControl();
        if (hwnd == hwndText)
        {
            return OS.CallWindowProc(EditProc, hwnd, msg, wParam, lParam);
        }

        int hwndList = getComboControl();
        if (hwnd == hwndList)
        {
            return OS.CallWindowProc(ListProc, hwnd, msg, wParam, lParam);
        }

        return OS.DefWindowProc(hwnd, msg, wParam, lParam);
    }

    /**
     * The system calls this function before activating, creating, destroying,
     * minimizing, maximizing, moving, or sizing a window; before completing a
     * system command; before removing a mouse or keyboard event from the system
     * message queue; before setting the keyboard focus; or before synchronizing
     * with the system message queue. A computer-based training (CBT)
     * application uses this hook procedure to receive useful notifications from
     * the system.
     * 
     * @param nCode Message code
     * @param wParam WPARAM
     * @param lParam LPARAM
     */
    int CBTProc(int nCode, int wParam, int lParam)
    {
        if (nCode == OS.HCBT_CREATEWND)
        {
            TCHAR buffer = new TCHAR(0, 128);
            OS.GetClassName(wParam, buffer, buffer.length());
            String className = buffer.toString(0, buffer.strlen());

            if (className.equals("Edit") || className.equals("EDIT"))
            {
                int bits = OS.GetWindowLong(wParam, OS.GWL_STYLE);
                OS.SetWindowLong(wParam, OS.GWL_STYLE, bits & ~OS.ES_NOHIDESEL);
            }
        }

        return OS.CallNextHookEx(cbtHook, nCode, wParam, lParam);
    }

    /**
     * Checks if provided handle is one of the handles used by ComboBoxEx controls.
     * 
     * @param hwnd Handle
     * @return true if handle is from ComboBoxEx
     */
    boolean checkHandle(int hwnd)
    {
        return hwnd == handle || hwnd == getEditControl() || hwnd == getComboControl();
    }

    /**
     * Checks that this class can be subclassed.
     * <p>
     * The SWT class library is intended to be subclassed 
     * only at specific, controlled points (most notably, 
     * <code>Composite</code> and <code>Canvas</code> when
     * implementing new widgets). This method enforces this
     * rule unless it is overridden.
     * </p>
     * Throws exception when subclassing is not possible.
     */
    protected void checkSubclass()
    {
        if (!isValidSubclass())
        {
            error(SWT.ERROR_INVALID_SUBCLASS);
        }
    }

    /**
     * Checks provide style and applies modifications to it if necessary.
     * 
     * @param style Current style
     * @return New style
     */
    static int checkStyle(int style)
    {
        /*
         * Feature in Windows. It is not possible to create a combo box that has
         * a border using Windows style bits. All combo boxes draw their own
         * border and do not use the standard Windows border styles. Therefore,
         * no matter what style bits are specified, clear the BORDER bits so
         * that the SWT style will match the Windows widget.
         * 
         * The Windows behavior is currently implemented on all platforms.
         */
        style &= ~SWT.BORDER;

        /*
         * Even though it is legal to create this widget with scroll bars, they
         * serve no useful purpose because they do not automatically scroll the
         * widget's client area. The fix is to clear the SWT style.
         */
        style &= ~(SWT.H_SCROLL | SWT.V_SCROLL);
        style = checkBits(style, SWT.DROP_DOWN, SWT.SIMPLE, 0, 0, 0, 0);

        if ((style & SWT.SIMPLE) != 0)
        {
            return style & ~SWT.READ_ONLY;
        }

        return style;
    }

    /**
     * Sets the selection in the receiver's text field to an empty selection
     * starting just before the first character. If the text field is editable,
     * this has the effect of placing the i-beam at the start of the text.
     * <p>
     * Note: To clear the selected items in the receiver's list, use
     * <code>deselectAll()</code>.
     * </p>
     */
    public void clearSelection()
    {
        checkWidget();
        OS.SendMessage(handle, OS.CB_SETEDITSEL, 0, -1);
    }

    /**
     * Returns the preferred size of the receiver.
     * <p>
     * The <em>preferred size</em> of a control is the size that it would best
     * be displayed at. The width hint and height hint arguments allow the
     * caller to ask a control questions such as "Given a particular width, how
     * high does the control need to be to show all of the contents?" To
     * indicate that the caller does not wish to constrain a particular
     * dimension, the constant <code>SWT.DEFAULT</code> is passed for the hint.
     * </p>
     * <p>
     * If the changed flag is <code>true</code>, it indicates that the
     * receiver's <em>contents</em> have changed, therefore any caches that a
     * layout manager containing the control may have been keeping need to be
     * flushed. When the control is resized, the changed flag will be
     * <code>false</code>, so layout manager caches can be retained.
     * </p>
     * 
     * @param wHint the width hint (can be <code>SWT.DEFAULT</code>)
     * @param hHint the height hint (can be <code>SWT.DEFAULT</code>)
     * @param changed <code>true</code> if the control's contents have changed,
     *            and <code>false</code> otherwise
     * @return the preferred size of the control.
     */
    public Point computeSize(int wHint, int hHint, boolean changed)
    {
        checkWidget();

        int width = 0;
        int height = 0;

        if (wHint == SWT.DEFAULT)
        {
            int newFont;
            int oldFont = 0;
            int hDC = OS.GetDC(handle);

            newFont = OS.SendMessage(handle, OS.WM_GETFONT, 0, 0);
            if (newFont != 0)
            {
                oldFont = OS.SelectObject(hDC, newFont);
            }

            int count = OS.SendMessage(handle, OS.CB_GETCOUNT, 0, 0);

            int flags = OS.DT_CALCRECT | OS.DT_NOPREFIX;
            if ((style & SWT.READ_ONLY) == 0)
            {
                flags |= OS.DT_EDITCONTROL;
            }

            int length = OS.GetWindowTextLength(handle);
            int cp = getCodePage();

            RECT rect = new RECT();
            TCHAR buffer = new TCHAR(cp, length + 1);
            OS.GetWindowText(handle, buffer, length + 1);
            OS.DrawText(hDC, buffer, length, rect, flags);

            width = Math.max(width, rect.right - rect.left);

            if ((style & SWT.H_SCROLL) != 0)
            {
                width = Math.max(width, scrollWidth);
            }
            else
            {
                for (int i = 0; i < count; i++)
                {
                    length = OS.SendMessage(handle, OS.CB_GETLBTEXTLEN, i, 0);
                    if (length != OS.CB_ERR)
                    {
                        if (length + 1 > buffer.length())
                        {
                            buffer = new TCHAR(cp, length + 1);
                        }

                        int result = OS.SendMessage(handle, OS.CB_GETLBTEXT, i, buffer);

                        if (result != OS.CB_ERR)
                        {
                            OS.DrawText(hDC, buffer, length, rect, flags);
                            width = Math.max(width, rect.right - rect.left);
                        }
                    }
                }
            }

            if (newFont != 0)
            {
                OS.SelectObject(hDC, oldFont);
            }

            OS.ReleaseDC(handle, hDC);
        }

        if (hHint == SWT.DEFAULT)
        {
            if ((style & SWT.SIMPLE) != 0)
            {
                int count = OS.SendMessage(handle, OS.CB_GETCOUNT, 0, 0);
                int itemHeight = OS.SendMessage(handle, OS.CB_GETITEMHEIGHT, 0, 0);

                height = count * itemHeight;
            }
        }

        if (width == 0)
        {
            width = DEFAULT_WIDTH;
        }

        if (height == 0)
        {
            height = DEFAULT_HEIGHT;
        }

        if (wHint != SWT.DEFAULT)
        {
            width = wHint;
        }

        if (hHint != SWT.DEFAULT)
        {
            height = hHint;
        }

        if ((style & SWT.READ_ONLY) != 0)
        {
            width += 8;
        }
        else
        {
            int hwndText = getEditControl();

            if (hwndText != 0)
            {
                int margins = OS.SendMessage(hwndText, OS.EM_GETMARGINS, 0, 0);
                int marginWidth = OS.LOWORD(margins) + OS.HIWORD(margins);

                width += marginWidth + 3;
            }
        }

        COMBOBOXINFO pcbi = new COMBOBOXINFO();
        pcbi.cbSize = COMBOBOXINFO.sizeof;

        if (((style & SWT.SIMPLE) == 0) && !OS.IsWinCE && OS.GetComboBoxInfo(handle, pcbi))
        {
            width += pcbi.itemLeft + (pcbi.buttonRight - pcbi.buttonLeft);
            height = (pcbi.buttonBottom - pcbi.buttonTop) + pcbi.buttonTop * 2;
        }
        else
        {
            int border = OS.GetSystemMetrics(OS.SM_CXEDGE);
            int textHeight = OS.SendMessage(handle, OS.CB_GETITEMHEIGHT, -1, 0);

            width += OS.GetSystemMetrics(OS.SM_CXVSCROLL) + border * 2;

            if (imageList != null)
            {
                width += imageList.getImageSize().x + 6;
            }

            if ((style & SWT.DROP_DOWN) != 0)
            {
                height = textHeight + 6;
            }
            else
            {
                height += textHeight + 10;
            }
        }

        if ((style & SWT.SIMPLE) != 0 && (style & SWT.H_SCROLL) != 0)
        {
            height += OS.GetSystemMetrics(OS.SM_CYHSCROLL);
        }

        return new Point(width, height);
    }

    /**
     * Copies the selected text. The current selection is copied to the
     * clipboard.
     */
    public void copy()
    {
        checkWidget();
        OS.SendMessage(handle, OS.WM_COPY, 0, 0);
    }

    /**
     * Creates handle - underlying widgets.
     */
    void createHandle()
    {
        /*
         * Feature in Windows. When the selection changes in a combo box,
         * Windows draws the selection, even when the combo box does not have
         * focus. Strictly speaking, this is the correct Windows behavior
         * because the combo box sets ES_NOHIDESEL on the text control that it
         * creates. Despite this, it looks strange because Windows also clears
         * the selection and selects all the text when the combo box gets focus.
         * The fix is use the CBT hook to clear the ES_NOHIDESEL style bit when
         * the text control is created.
         */
        if (OS.IsWinCE)
        {
            super.createHandle();
        }
        else
        {
            int threadId = OS.GetCurrentThreadId();

            Callback cbtCallback = new Callback(this, "CBTProc", 3);
            int cbtProc = cbtCallback.getAddress();
            if (cbtProc == 0)
            {
                error(SWT.ERROR_NO_MORE_CALLBACKS);
            }
            cbtHook = OS.SetWindowsHookEx(OS.WH_CBT, cbtProc, 0, threadId);

            /*
             * This part of the code had to be placed here, the reason is that
             * ComboBoxEx sets default (non-modifiable?) height of the combo list
             * during widget creation.
             */

            int hwndParent = widgetParent();
            handle = OS.CreateWindowEx(widgetExtStyle(), windowClass(), null, widgetStyle(), 0, 
                    0, 0, 500, hwndParent, 0, OS.GetModuleHandle(null), widgetCreateStruct());

            if (handle == 0)
            {
                error(SWT.ERROR_NO_HANDLES);
            }

            int bits = OS.GetWindowLong(handle, OS.GWL_STYLE);
            if ((bits & OS.WS_CHILD) != 0)
            {
                OS.SetWindowLongPtr(handle, OS.GWLP_ID, handle);
            }

            if (OS.IsDBLocale && hwndParent != 0)
            {
                int hIMC = OS.ImmGetContext(hwndParent);
                OS.ImmAssociateContext(handle, hIMC);
                OS.ImmReleaseContext(hwndParent, hIMC);
            }

            state |= CANVAS;

            if ((style & (SWT.H_SCROLL | SWT.V_SCROLL)) == 0)
            {
                state |= THEME_BACKGROUND;
            }

            if ((style & SWT.TRANSPARENT) != 0)
            {
                int exbits = OS.GetWindowLong(handle, OS.GWL_EXSTYLE) | OS.WS_EX_TRANSPARENT;
                OS.SetWindowLong(handle, OS.GWL_EXSTYLE, exbits);
            }

            if (cbtHook != 0)
            {
                OS.UnhookWindowsHookEx(cbtHook);
            }

            cbtHook = 0;
            cbtCallback.dispose();
        }

        state &= ~(CANVAS | THEME_BACKGROUND);

        int hwndText = getEditControl();
        if (hwndText != 0 && EditProc == 0)
        {
            EditProc = OS.GetWindowLongPtr(hwndText, OS.GWLP_WNDPROC);
        }

        int hwndList = getComboControl();
        if (hwndList != 0 && ListProc == 0)
        {
            ListProc = OS.GetWindowLongPtr(hwndList, OS.GWLP_WNDPROC);
        }

        /*
         * Bug in Windows. If the combo box has the CBS_SIMPLE style, the list
         * portion of the combo box is not drawn correctly the first time,
         * causing pixel corruption. The fix is to ensure that the combo box has
         * been resized more than once.
         */
        if ((style & SWT.SIMPLE) != 0)
        {
            int flags = OS.SWP_NOZORDER | OS.SWP_DRAWFRAME | OS.SWP_NOACTIVATE;
            SetWindowPos(handle, 0, 0, 0, 0x3FFF, 0x3FFF, flags);
            SetWindowPos(handle, 0, 0, 0, 0, 0, flags);
        }
    }

    /**
     * Cuts the selected text. The current selection is first copied to the
     * clipboard and then deleted from the widget.
     */
    public void cut()
    {
        checkWidget();

        if ((style & SWT.READ_ONLY) != 0)
        {
            return;
        }

        OS.SendMessage(handle, OS.WM_CUT, 0, 0);
    }

    /**
     * Returns default background.
     * 
     * @param color
     */
    int defaultBackground()
    {
        return OS.GetSysColor(OS.COLOR_WINDOW);
    }

    /**
     * Remove control from display.
     */
    void deregister()
    {
        super.deregister();

        int hwndText = getEditControl();
        if (hwndText != 0)
        {
            display.removeControl(hwndText);
        }

        int hwndList = getComboControl();
        if (hwndList != 0)
        {
            display.removeControl(hwndList);
        }
    }

    /**
     * Deselects the item at the given zero-relative index in the receiver's
     * list. If the item at the index was already deselected, it remains
     * deselected. Indices that are out of range are ignored.
     * 
     * @param index the index of the item to deselect
     */
    public void deselect(int index)
    {
        checkWidget();

        int selection = OS.SendMessage(handle, OS.CB_GETCURSEL, 0, 0);
        if (index != selection)
        {
            return;
        }

        OS.SendMessage(handle, OS.CB_SETCURSEL, -1, 0);
        sendEvent(SWT.Modify);
    }

    /**
     * Deselects all selected items in the receiver's list.
     * <p>
     * Note: To clear the selection in the receiver's text field, use
     * <code>clearSelection()</code>.
     */
    public void deselectAll()
    {
        checkWidget();

        OS.SendMessage(handle, OS.CB_SETCURSEL, -1, 0);
        sendEvent(SWT.Modify);
    }

    /**
     * TODO CLEAN/COMMENT
     */
    boolean dragDetect(int /* long */hwnd, int x, int y, boolean filter, boolean[] detect,
        boolean[] consume)
    {
        if (filter && (style & SWT.READ_ONLY) == 0)
        {
            int /* long */hwndText = getEditControl();
            if (hwndText != 0)
            {
                int[] start = new int[1], end = new int[1];
                OS.SendMessage(handle, OS.CB_GETEDITSEL, start, end);
                if (start[0] != end[0])
                {
                    int /* long */lParam = OS.MAKELPARAM(x, y);
                    int position =
                        OS.LOWORD(OS.SendMessage(hwndText, OS.EM_CHARFROMPOS, 0, lParam));
                    if (start[0] <= position && position < end[0])
                    {
                        if (super.dragDetect(hwnd, x, y, filter, detect, consume))
                        {
                            if (consume != null)
                                consume[0] = true;
                            return true;
                        }
                    }
                }
                return false;
            }
        }
        return super.dragDetect(hwnd, x, y, filter, detect, consume);
    }

    /**
     * Returns combo/list control of ComboBoxEx.
     * 
     * @return handle to combo/list control
     */
    private int getComboControl()
    {
        return OS.SendMessage(handle, CBEM_GETCOMBOCONTROL, 0, 0);
    }

    /**
     * Returns edit control of ComboBoxEx.
     * 
     * @return handle to edit control
     */
    private int getEditControl()
    {
        return OS.SendMessage(handle, CBEM_GETEDITCONTROL, 0, 0);
    }

    /**
     * TODO CLEAN/COMMENT
     */
    /**
     * Returns the item at the given, zero-relative index in the receiver's
     * list. Throws an exception if the index is out of range.
     * 
     * @param index the index of the item to return
     * @return the item at the given index
     * 
     * @exception IllegalArgumentException <ul>
     *                <li>ERROR_INVALID_RANGE - if the index is not between 0
     *                and the number of elements in the list minus 1 (inclusive)
     *                </li>
     *                </ul>
     * @exception SWTException <ul>
     *                <li>ERROR_WIDGET_DISPOSED - if the receiver has been
     *                disposed</li>
     *                <li>ERROR_THREAD_INVALID_ACCESS - if not called from the
     *                thread that created the receiver</li>
     *                </ul>
     */
    public String getItem(int index)
    {
        checkWidget();
        int length = (int) /* 64 */OS.SendMessage(handle, OS.CB_GETLBTEXTLEN, index, 0);
        if (length != OS.CB_ERR)
        {
            TCHAR buffer = new TCHAR(getCodePage(), length + 1);
            int result = (int) /* 64 */OS.SendMessage(handle, OS.CB_GETLBTEXT, index, buffer);
            if (result != OS.CB_ERR)
                return buffer.toString(0, length);
        }
        int count = (int) /* 64 */OS.SendMessage(handle, OS.CB_GETCOUNT, 0, 0);
        if (0 <= index && index < count)
            error(SWT.ERROR_CANNOT_GET_ITEM);
        error(SWT.ERROR_INVALID_RANGE);
        return "";
    }

    /**
     * Returns the number of items contained in the receiver's list.
     * 
     * @return the number of items
     */
    public int getItemCount()
    {
        checkWidget();

        int count = OS.SendMessage(handle, OS.CB_GETCOUNT, 0, 0);
        if (count == OS.CB_ERR)
        {
            error(SWT.ERROR_CANNOT_GET_COUNT);
        }

        return count;
    }

    /**
     * Returns the height of the area which would be used to display
     * <em>one</em> of the items in the receiver's list.
     * 
     * @return the height of one item
     */
    public int getItemHeight()
    {
        checkWidget();

        int result = OS.SendMessage(handle, OS.CB_GETITEMHEIGHT, 0, 0);
        if (result == OS.CB_ERR)
        {
            error(SWT.ERROR_CANNOT_GET_ITEM_HEIGHT);
        }

        return result;
    }

    /**
     * TODO CLEAN/COMMENT
     */
    /**
     * Returns a (possibly empty) array of <code>String</code>s which are the
     * items in the receiver's list.
     * <p>
     * Note: This is not the actual structure used by the receiver to maintain
     * its list of items, so modifying the array will not affect the receiver.
     * </p>
     * 
     * @return the items in the receiver's list
     * 
     * @exception SWTException <ul>
     *                <li>ERROR_WIDGET_DISPOSED - if the receiver has been
     *                disposed</li>
     *                <li>ERROR_THREAD_INVALID_ACCESS - if not called from the
     *                thread that created the receiver</li>
     *                </ul>
     */
    public String[] getItems()
    {
        checkWidget();
        int count = getItemCount();
        String[] result = new String[count];
        for (int i = 0; i < count; i++)
            result[i] = getItem(i);
        return result;
    }

    /**
     * Returns <code>true</code> if the receiver's list is visible, and
     * <code>false</code> otherwise.
     * <p>
     * If one of the receiver's ancestors is not visible or some other condition
     * makes the receiver not visible, this method may still indicate that it is
     * considered visible even though it may not actually be showing.
     * </p>
     * 
     * @return the receiver's list's visibility state
     */
    public boolean getListVisible()
    {
        checkWidget();

        if ((style & SWT.DROP_DOWN) != 0)
        {
            return OS.SendMessage(handle, OS.CB_GETDROPPEDSTATE, 0, 0) != 0;
        }

        return true;
    }

    /**
     * TODO CLEAN/COMMENT
     */
    String getNameText()
    {
        return getText();
    }

    /**
     * Marks the receiver's list as visible if the argument is <code>true</code>
     * , and marks it invisible otherwise.
     * <p>
     * If one of the receiver's ancestors is not visible or some other condition
     * makes the receiver not visible, marking it visible may not actually cause
     * it to be displayed.
     * </p>
     * 
     * @param visible the new visibility state
     */
    public void setListVisible(boolean visible)
    {
        checkWidget();
        OS.SendMessage(handle, OS.CB_SHOWDROPDOWN, visible ? 1 : 0, 0);
    }

    /**
     * Returns the orientation of the receiver.
     * 
     * @return the orientation style
     */
    public int getOrientation()
    {
        checkWidget();
        return style & (SWT.LEFT_TO_RIGHT | SWT.RIGHT_TO_LEFT);
    }

    /**
     * TODO CLEAN/COMMENT
     */
    /**
     * Returns a <code>Point</code> whose x coordinate is the character position
     * representing the start of the selection in the receiver's text field, and
     * whose y coordinate is the character position representing the end of the
     * selection. An "empty" selection is indicated by the x and y coordinates
     * having the same value.
     * <p>
     * Indexing is zero based. The range of a selection is from 0..N where N is
     * the number of characters in the widget.
     * </p>
     * 
     * @return a point representing the selection start and end
     * 
     * @exception SWTException <ul>
     *                <li>ERROR_WIDGET_DISPOSED - if the receiver has been
     *                disposed</li>
     *                <li>ERROR_THREAD_INVALID_ACCESS - if not called from the
     *                thread that created the receiver</li>
     *                </ul>
     */
    public Point getSelection()
    {
        checkWidget();
        if ((style & SWT.DROP_DOWN) != 0 && (style & SWT.READ_ONLY) != 0)
        {
            return new Point(0, OS.GetWindowTextLength(handle));
        }
        int[] start = new int[1], end = new int[1];
        OS.SendMessage(handle, OS.CB_GETEDITSEL, start, end);
        if (!OS.IsUnicode && OS.IsDBLocale)
        {
            start[0] = mbcsToWcsPos(start[0]);
            end[0] = mbcsToWcsPos(end[0]);
        }
        return new Point(start[0], end[0]);
    }

    /**
     * TODO CLEAN/COMMENT
     */
    /**
     * Returns the zero-relative index of the item which is currently selected
     * in the receiver's list, or -1 if no item is selected.
     * 
     * @return the index of the selected item
     * 
     * @exception SWTException <ul>
     *                <li>ERROR_WIDGET_DISPOSED - if the receiver has been
     *                disposed</li>
     *                <li>ERROR_THREAD_INVALID_ACCESS - if not called from the
     *                thread that created the receiver</li>
     *                </ul>
     */
    public int getSelectionIndex()
    {
        checkWidget();
        if (noSelection)
            return -1;
        return (int) /* 64 */OS.SendMessage(handle, OS.CB_GETCURSEL, 0, 0);
    }

    /**
     * TODO CLEAN/COMMENT
     */
    /**
     * Returns a string containing a copy of the contents of the receiver's text
     * field, or an empty string if there are no contents.
     * 
     * @return the receiver's text
     * 
     * @exception SWTException <ul>
     *                <li>ERROR_WIDGET_DISPOSED - if the receiver has been
     *                disposed</li>
     *                <li>ERROR_THREAD_INVALID_ACCESS - if not called from the
     *                thread that created the receiver</li>
     *                </ul>
     */
    public String getText()
    {
        checkWidget();
        int length = OS.GetWindowTextLength(handle);
        if (length == 0)
            return "";
        TCHAR buffer = new TCHAR(getCodePage(), length + 1);
        OS.GetWindowText(handle, buffer, length + 1);
        return buffer.toString(0, length);
    }

    /**
     * TODO CLEAN/COMMENT
     */
    /**
     * Returns the height of the receivers's text field.
     * 
     * @return the text height
     * 
     * @exception SWTException <ul>
     *                <li>ERROR_WIDGET_DISPOSED - if the receiver has been
     *                disposed</li>
     *                <li>ERROR_THREAD_INVALID_ACCESS - if not called from the
     *                thread that created the receiver</li>
     *                </ul>
     */
    public int getTextHeight()
    {
        checkWidget();
        COMBOBOXINFO pcbi = new COMBOBOXINFO();
        pcbi.cbSize = COMBOBOXINFO.sizeof;
        if (((style & SWT.SIMPLE) == 0) && !OS.IsWinCE && OS.GetComboBoxInfo(handle, pcbi))
        {
            return (pcbi.buttonBottom - pcbi.buttonTop) + pcbi.buttonTop * 2;
        }
        int result = (int) /* 64 */OS.SendMessage(handle, OS.CB_GETITEMHEIGHT, -1, 0);
        if (result == OS.CB_ERR)
            error(SWT.ERROR_CANNOT_GET_ITEM_HEIGHT);
        return (style & SWT.DROP_DOWN) != 0 ? result + 6 : result + 10;
    }

    /**
     * TODO CLEAN/COMMENT
     */
    /**
     * Returns the maximum number of characters that the receiver's text field
     * is capable of holding. If this has not been changed by
     * <code>setTextLimit()</code>, it will be the constant
     * <code>Combo.LIMIT</code>.
     * 
     * @return the text limit
     * 
     * @exception SWTException <ul>
     *                <li>ERROR_WIDGET_DISPOSED - if the receiver has been
     *                disposed</li>
     *                <li>ERROR_THREAD_INVALID_ACCESS - if not called from the
     *                thread that created the receiver</li>
     *                </ul>
     * 
     * @see #LIMIT
     */
    public int getTextLimit()
    {
        checkWidget();
        int /* long */hwndText = getEditControl();
        if (hwndText == 0)
            return LIMIT;
        return (int) /* 64 */OS.SendMessage(hwndText, OS.EM_GETLIMITTEXT, 0, 0) & 0x7FFFFFFF;
    }

    /**
     * TODO CLEAN/COMMENT
     */
    /**
     * Gets the number of items that are visible in the drop down portion of the
     * receiver's list.
     * <p>
     * Note: This operation is a hint and is not supported on platforms that do
     * not have this concept.
     * </p>
     * 
     * @return the number of items that are visible
     * 
     * @exception SWTException <ul>
     *                <li>ERROR_WIDGET_DISPOSED - if the receiver has been
     *                disposed</li>
     *                <li>ERROR_THREAD_INVALID_ACCESS - if not called from the
     *                thread that created the receiver</li>
     *                </ul>
     * 
     * @since 3.0
     */
    public int getVisibleItemCount()
    {
        checkWidget();
        return visibleCount;
    }

    /**
     * TODO CLEAN/COMMENT
     */
    boolean hasFocus()
    {
        int /* long */hwndFocus = OS.GetFocus();
        if (hwndFocus == handle)
            return true;
        if (hwndFocus == 0)
            return false;
        int /* long */hwndText = getEditControl();
        if (hwndFocus == hwndText)
            return true;
        int /* long */hwndList = getComboControl();
        if (hwndFocus == hwndList)
            return true;
        return false;
    }

    /**
     * TODO CLEAN/COMMENT
     */
    /**
     * Searches the receiver's list starting at the first item (index 0) until
     * an item is found that is equal to the argument, and returns the index of
     * that item. If no item is found, returns -1.
     * 
     * @param string the search item
     * @return the index of the item
     * 
     * @exception IllegalArgumentException <ul>
     *                <li>ERROR_NULL_ARGUMENT - if the string is null</li>
     *                </ul>
     * @exception SWTException <ul>
     *                <li>ERROR_WIDGET_DISPOSED - if the receiver has been
     *                disposed</li>
     *                <li>ERROR_THREAD_INVALID_ACCESS - if not called from the
     *                thread that created the receiver</li>
     *                </ul>
     */
    public int indexOf(String string)
    {
        return indexOf(string, 0);
    }

    /**
     * TODO CLEAN/COMMENT
     */
    /**
     * Searches the receiver's list starting at the given, zero-relative index
     * until an item is found that is equal to the argument, and returns the
     * index of that item. If no item is found or the starting index is out of
     * range, returns -1.
     * 
     * @param string the search item
     * @param start the zero-relative index at which to begin the search
     * @return the index of the item
     * 
     * @exception IllegalArgumentException <ul>
     *                <li>ERROR_NULL_ARGUMENT - if the string is null</li>
     *                </ul>
     * @exception SWTException <ul>
     *                <li>ERROR_WIDGET_DISPOSED - if the receiver has been
     *                disposed</li>
     *                <li>ERROR_THREAD_INVALID_ACCESS - if not called from the
     *                thread that created the receiver</li>
     *                </ul>
     */
    public int indexOf(String string, int start)
    {
        checkWidget();
        if (string == null)
            error(SWT.ERROR_NULL_ARGUMENT);

        /*
         * Bug in Windows. For some reason, CB_FINDSTRINGEXACT will not find
         * empty strings even though it is legal to insert an empty string into
         * a combo. The fix is to search the combo, an item at a time.
         */
        if (string.length() == 0)
        {
            int count = getItemCount();
            for (int i = start; i < count; i++)
            {
                if (string.equals(getItem(i)))
                    return i;
            }
            return -1;
        }

        /* Use CB_FINDSTRINGEXACT to search for the item */
        int count = (int) /* 64 */OS.SendMessage(handle, OS.CB_GETCOUNT, 0, 0);
        if (!(0 <= start && start < count))
            return -1;
        int index = start - 1, last = 0;
        TCHAR buffer = new TCHAR(getCodePage(), string, true);
        do
        {
            index =
                (int) /* 64 */OS.SendMessage(handle, OS.CB_FINDSTRINGEXACT, last = index, buffer);
            if (index == OS.CB_ERR || index <= last)
                return -1;
        }
        while (!string.equals(getItem(index)));
        return index;
    }

    /**
     * TODO CLEAN/COMMENT
     */
    int mbcsToWcsPos(int mbcsPos)
    {
        if (mbcsPos <= 0)
            return 0;
        if (OS.IsUnicode)
            return mbcsPos;
        int /* long */hwndText = getEditControl();
        if (hwndText == 0)
            return mbcsPos;
        int mbcsSize = OS.GetWindowTextLengthA(hwndText);
        if (mbcsSize == 0)
            return 0;
        if (mbcsPos >= mbcsSize)
            return mbcsSize;
        byte[] buffer = new byte[mbcsSize + 1];
        OS.GetWindowTextA(hwndText, buffer, mbcsSize + 1);
        return OS.MultiByteToWideChar(getCodePage(), OS.MB_PRECOMPOSED, buffer, mbcsPos, null, 0);
    }

    /**
     * TODO CLEAN/COMMENT
     */
    /**
     * Pastes text from clipboard.
     * <p>
     * The selected text is deleted from the widget and new text inserted from
     * the clipboard.
     * </p>
     * 
     * @exception SWTException <ul>
     *                <li>ERROR_WIDGET_DISPOSED - if the receiver has been
     *                disposed</li>
     *                <li>ERROR_THREAD_INVALID_ACCESS - if not called from the
     *                thread that created the receiver</li>
     *                </ul>
     * 
     * @since 2.1
     */
    public void paste()
    {
        checkWidget();
        if ((style & SWT.READ_ONLY) != 0)
            return;
        OS.SendMessage(handle, OS.WM_PASTE, 0, 0);
    }

    /**
     * TODO CLEAN/COMMENT
     */
    void register()
    {
        super.register();
        int /* long */hwndText = getEditControl();
        if (hwndText != 0)
            display.addControl(hwndText, this);
        int /* long */hwndList = getComboControl();
        if (hwndList != 0)
            display.addControl(hwndList, this);
    }

    /**
     * TODO CLEAN/COMMENT
     */
    /**
     * Removes the item from the receiver's list at the given zero-relative
     * index.
     * 
     * @param index the index for the item
     * 
     * @exception IllegalArgumentException <ul>
     *                <li>ERROR_INVALID_RANGE - if the index is not between 0
     *                and the number of elements in the list minus 1 (inclusive)
     *                </li>
     *                </ul>
     * @exception SWTException <ul>
     *                <li>ERROR_WIDGET_DISPOSED - if the receiver has been
     *                disposed</li>
     *                <li>ERROR_THREAD_INVALID_ACCESS - if not called from the
     *                thread that created the receiver</li>
     *                </ul>
     */
    public void remove(int index)
    {
        checkWidget();
        remove(index, true);
    }

    /**
     * TODO CLEAN/COMMENT
     */
    void remove(int index, boolean notify)
    {
        TCHAR buffer = null;
        if ((style & SWT.H_SCROLL) != 0)
        {
            int length = (int) /* 64 */OS.SendMessage(handle, OS.CB_GETLBTEXTLEN, index, 0);
            if (length == OS.CB_ERR)
            {
                int count = (int) /* 64 */OS.SendMessage(handle, OS.CB_GETCOUNT, 0, 0);
                if (0 <= index && index < count)
                    error(SWT.ERROR_ITEM_NOT_REMOVED);
                error(SWT.ERROR_INVALID_RANGE);
            }
            buffer = new TCHAR(getCodePage(), length + 1);
            int result = (int) /* 64 */OS.SendMessage(handle, OS.CB_GETLBTEXT, index, buffer);
            if (result == OS.CB_ERR)
            {
                int count = (int) /* 64 */OS.SendMessage(handle, OS.CB_GETCOUNT, 0, 0);
                if (0 <= index && index < count)
                    error(SWT.ERROR_ITEM_NOT_REMOVED);
                error(SWT.ERROR_INVALID_RANGE);
            }
        }
        int length = OS.GetWindowTextLength(handle);
        int code = (int) /* 64 */OS.SendMessage(handle, OS.CB_DELETESTRING, index, 0);
        if (code == OS.CB_ERR)
        {
            int count = (int) /* 64 */OS.SendMessage(handle, OS.CB_GETCOUNT, 0, 0);
            if (0 <= index && index < count)
                error(SWT.ERROR_ITEM_NOT_REMOVED);
            error(SWT.ERROR_INVALID_RANGE);
        }
        if ((style & SWT.H_SCROLL) != 0)
            setScrollWidth(buffer, true);
        if (notify && length != OS.GetWindowTextLength(handle))
        {
            sendEvent(SWT.Modify);
            if (isDisposed())
                return;
        }
        /*
         * Bug in Windows. When the combo box is read only with exactly one item
         * that is currently selected and that item is removed, the combo box
         * does not redraw to clear the text area. The fix is to force a redraw.
         */
        if ((style & SWT.READ_ONLY) != 0)
        {
            int count = (int) /* 64 */OS.SendMessage(handle, OS.CB_GETCOUNT, 0, 0);
            if (count == 0)
                OS.InvalidateRect(handle, null, true);
        }
    }

    /**
     * TODO CLEAN/COMMENT
     */
    /**
     * Removes the items from the receiver's list which are between the given
     * zero-relative start and end indices (inclusive).
     * 
     * @param start the start of the range
     * @param end the end of the range
     * 
     * @exception IllegalArgumentException <ul>
     *                <li>ERROR_INVALID_RANGE - if either the start or end are
     *                not between 0 and the number of elements in the list minus
     *                1 (inclusive)</li>
     *                </ul>
     * @exception SWTException <ul>
     *                <li>ERROR_WIDGET_DISPOSED - if the receiver has been
     *                disposed</li>
     *                <li>ERROR_THREAD_INVALID_ACCESS - if not called from the
     *                thread that created the receiver</li>
     *                </ul>
     */
    public void remove(int start, int end)
    {
        checkWidget();
        if (start > end)
            return;
        int count = (int) /* 64 */OS.SendMessage(handle, OS.CB_GETCOUNT, 0, 0);
        if (!(0 <= start && start <= end && end < count))
        {
            error(SWT.ERROR_INVALID_RANGE);
        }
        int textLength = OS.GetWindowTextLength(handle);
        RECT rect = null;
        int /* long */hDC = 0, oldFont = 0, newFont = 0;
        int newWidth = 0;
        if ((style & SWT.H_SCROLL) != 0)
        {
            rect = new RECT();
            hDC = OS.GetDC(handle);
            newFont = OS.SendMessage(handle, OS.WM_GETFONT, 0, 0);
            if (newFont != 0)
                oldFont = OS.SelectObject(hDC, newFont);
        }
        int cp = getCodePage();
        int flags = OS.DT_CALCRECT | OS.DT_SINGLELINE | OS.DT_NOPREFIX;
        for (int i = start; i <= end; i++)
        {
            TCHAR buffer = null;
            if ((style & SWT.H_SCROLL) != 0)
            {
                int length = (int) /* 64 */OS.SendMessage(handle, OS.CB_GETLBTEXTLEN, start, 0);
                if (length == OS.CB_ERR)
                    break;
                buffer = new TCHAR(cp, length + 1);
                int result = (int) /* 64 */OS.SendMessage(handle, OS.CB_GETLBTEXT, start, buffer);
                if (result == OS.CB_ERR)
                    break;
            }
            int result = (int) /* 64 */OS.SendMessage(handle, OS.CB_DELETESTRING, start, 0);
            if (result == OS.CB_ERR)
                error(SWT.ERROR_ITEM_NOT_REMOVED);
            if ((style & SWT.H_SCROLL) != 0)
            {
                OS.DrawText(hDC, buffer, -1, rect, flags);
                newWidth = Math.max(newWidth, rect.right - rect.left);
            }
        }
        if ((style & SWT.H_SCROLL) != 0)
        {
            if (newFont != 0)
                OS.SelectObject(hDC, oldFont);
            OS.ReleaseDC(handle, hDC);
            setScrollWidth(newWidth, false);
        }
        if (textLength != OS.GetWindowTextLength(handle))
        {
            sendEvent(SWT.Modify);
            if (isDisposed())
                return;
        }
        /*
         * Bug in Windows. When the combo box is read only with exactly one item
         * that is currently selected and that item is removed, the combo box
         * does not redraw to clear the text area. The fix is to force a redraw.
         */
        if ((style & SWT.READ_ONLY) != 0)
        {
            count = (int) /* 64 */OS.SendMessage(handle, OS.CB_GETCOUNT, 0, 0);
            if (count == 0)
                OS.InvalidateRect(handle, null, true);
        }
    }

    /**
     * TODO CLEAN/COMMENT
     */
    /**
     * Searches the receiver's list starting at the first item until an item is
     * found that is equal to the argument, and removes that item from the list.
     * 
     * @param string the item to remove
     * 
     * @exception IllegalArgumentException <ul>
     *                <li>ERROR_NULL_ARGUMENT - if the string is null</li>
     *                <li>ERROR_INVALID_ARGUMENT - if the string is not found in
     *                the list</li>
     *                </ul>
     * @exception SWTException <ul>
     *                <li>ERROR_WIDGET_DISPOSED - if the receiver has been
     *                disposed</li>
     *                <li>ERROR_THREAD_INVALID_ACCESS - if not called from the
     *                thread that created the receiver</li>
     *                </ul>
     */
    public void remove(String string)
    {
        checkWidget();
        if (string == null)
            error(SWT.ERROR_NULL_ARGUMENT);
        int index = indexOf(string, 0);
        if (index == -1)
            error(SWT.ERROR_INVALID_ARGUMENT);
        remove(index);
    }

    /**
     * TODO CLEAN/COMMENT
     */
    /**
     * Removes all of the items from the receiver's list and clear the contents
     * of receiver's text field.
     * <p>
     * @exception SWTException <ul>
     *                <li>ERROR_WIDGET_DISPOSED - if the receiver has been
     *                disposed</li> <li>ERROR_THREAD_INVALID_ACCESS - if not
     *                called from the thread that created the receiver</li>
     *                </ul>
     */
    public void removeAll()
    {
        checkWidget();
        OS.SendMessage(handle, OS.CB_RESETCONTENT, 0, 0);
        sendEvent(SWT.Modify);
        if (isDisposed())
            return;
        if ((style & SWT.H_SCROLL) != 0)
            setScrollWidth(0);
    }

    /**
     * TODO CLEAN/COMMENT
     */
    /**
     * Removes the listener from the collection of listeners who will be
     * notified when the receiver's text is modified.
     * 
     * @param listener the listener which should no longer be notified
     * 
     * @exception IllegalArgumentException <ul>
     *                <li>ERROR_NULL_ARGUMENT - if the listener is null</li>
     *                </ul>
     * @exception SWTException <ul>
     *                <li>ERROR_WIDGET_DISPOSED - if the receiver has been
     *                disposed</li>
     *                <li>ERROR_THREAD_INVALID_ACCESS - if not called from the
     *                thread that created the receiver</li>
     *                </ul>
     * 
     * @see ModifyListener
     * @see #addModifyListener
     */
    public void removeModifyListener(ModifyListener listener)
    {
        checkWidget();
        if (listener == null)
            error(SWT.ERROR_NULL_ARGUMENT);
        if (eventTable == null)
            return;
        eventTable.unhook(SWT.Modify, listener);
    }

    /**
     * TODO CLEAN/COMMENT
     */
    /**
     * Removes the listener from the collection of listeners who will be
     * notified when the user changes the receiver's selection.
     * 
     * @param listener the listener which should no longer be notified
     * 
     * @exception IllegalArgumentException <ul>
     *                <li>ERROR_NULL_ARGUMENT - if the listener is null</li>
     *                </ul>
     * @exception SWTException <ul>
     *                <li>ERROR_WIDGET_DISPOSED - if the receiver has been
     *                disposed</li>
     *                <li>ERROR_THREAD_INVALID_ACCESS - if not called from the
     *                thread that created the receiver</li>
     *                </ul>
     * 
     * @see SelectionListener
     * @see #addSelectionListener
     */
    public void removeSelectionListener(SelectionListener listener)
    {
        checkWidget();
        if (listener == null)
            error(SWT.ERROR_NULL_ARGUMENT);
        if (eventTable == null)
            return;
        eventTable.unhook(SWT.Selection, listener);
        eventTable.unhook(SWT.DefaultSelection, listener);
    }

    /**
     * TODO CLEAN/COMMENT
     */
    /**
     * Removes the listener from the collection of listeners who will be
     * notified when the control is verified.
     * 
     * @param listener the listener which should no longer be notified
     * 
     * @exception IllegalArgumentException <ul>
     *                <li>ERROR_NULL_ARGUMENT - if the listener is null</li>
     *                </ul>
     * @exception SWTException <ul>
     *                <li>ERROR_WIDGET_DISPOSED - if the receiver has been
     *                disposed</li>
     *                <li>ERROR_THREAD_INVALID_ACCESS - if not called from the
     *                thread that created the receiver</li>
     *                </ul>
     * 
     * @see VerifyListener
     * @see #addVerifyListener
     * 
     * @since 3.1
     */
    public void removeVerifyListener(VerifyListener listener)
    {
        checkWidget();
        if (listener == null)
            error(SWT.ERROR_NULL_ARGUMENT);
        if (eventTable == null)
            return;
        eventTable.unhook(SWT.Verify, listener);
    }

    /**
     * TODO CLEAN/COMMENT
     */
    boolean sendKeyEvent(int type, int msg, int /* long */wParam, int /* long */lParam, Event event)
    {
        if (!super.sendKeyEvent(type, msg, wParam, lParam, event))
        {
            return false;
        }
        if ((style & SWT.READ_ONLY) != 0)
            return true;
        if (type != SWT.KeyDown)
            return true;
        if (msg != OS.WM_CHAR && msg != OS.WM_KEYDOWN && msg != OS.WM_IME_CHAR)
        {
            return true;
        }
        if (event.character == 0)
            return true;
        if (!hooks(SWT.Verify) && !filters(SWT.Verify))
            return true;
        char key = event.character;
        int stateMask = event.stateMask;

        /*
         * Disable all magic keys that could modify the text and don't send
         * events when Alt, Shift or Ctrl is pressed.
         */
        switch (msg)
        {
            case OS.WM_CHAR:
                if (key != 0x08 && key != 0x7F && key != '\r' && key != '\t' && key != '\n')
                    break;
                // FALL THROUGH
            case OS.WM_KEYDOWN:
                if ((stateMask & (SWT.ALT | SWT.SHIFT | SWT.CONTROL)) != 0)
                    return false;
                break;
        }

        /*
         * If the left button is down, the text widget refuses the character.
         */
        if (OS.GetKeyState(OS.VK_LBUTTON) < 0)
        {
            return true;
        }

        /* Verify the character */
        String oldText = "";
        int[] start = new int[1], end = new int[1];
        int /* long */hwndText = getEditControl();
        if (hwndText == 0)
            return true;
        OS.SendMessage(hwndText, OS.EM_GETSEL, start, end);
        switch (key)
        {
            case 0x08: /* Bs */
                if (start[0] == end[0])
                {
                    if (start[0] == 0)
                        return true;
                    start[0] = start[0] - 1;
                    if (!OS.IsUnicode && OS.IsDBLocale)
                    {
                        int[] newStart = new int[1], newEnd = new int[1];
                        OS.SendMessage(hwndText, OS.EM_SETSEL, start[0], end[0]);
                        OS.SendMessage(hwndText, OS.EM_GETSEL, newStart, newEnd);
                        if (start[0] != newStart[0])
                            start[0] = start[0] - 1;
                    }
                    start[0] = Math.max(start[0], 0);
                }
                break;
            case 0x7F: /* Del */
                if (start[0] == end[0])
                {
                    int length = OS.GetWindowTextLength(hwndText);
                    if (start[0] == length)
                        return true;
                    end[0] = end[0] + 1;
                    if (!OS.IsUnicode && OS.IsDBLocale)
                    {
                        int[] newStart = new int[1], newEnd = new int[1];
                        OS.SendMessage(hwndText, OS.EM_SETSEL, start[0], end[0]);
                        OS.SendMessage(hwndText, OS.EM_GETSEL, newStart, newEnd);
                        if (end[0] != newEnd[0])
                            end[0] = end[0] + 1;
                    }
                    end[0] = Math.min(end[0], length);
                }
                break;
            case '\r': /* Return */
                return true;
            default: /* Tab and other characters */
                if (key != '\t' && key < 0x20)
                    return true;
                oldText = new String(new char[]{key});
                break;
        }
        String newText = verifyText(oldText, start[0], end[0], event);
        if (newText == null)
            return false;
        if (newText == oldText)
            return true;
        TCHAR buffer = new TCHAR(getCodePage(), newText, true);
        OS.SendMessage(hwndText, OS.EM_SETSEL, start[0], end[0]);
        OS.SendMessage(hwndText, OS.EM_REPLACESEL, 0, buffer);
        return false;
    }

    /**
     * TODO CLEAN/COMMENT
     */
    /**
     * Selects the item at the given zero-relative index in the receiver's list.
     * If the item at the index was already selected, it remains selected.
     * Indices that are out of range are ignored.
     * 
     * @param index the index of the item to select
     */
    public void select(int index)
    {
        checkWidget();

        int count = (int) OS.SendMessage(handle, OS.CB_GETCOUNT, 0, 0);

        if (0 <= index && index < count)
        {
            int selection = (int) OS.SendMessage(handle, OS.CB_GETCURSEL, 0, 0);
            int code = (int) OS.SendMessage(handle, OS.CB_SETCURSEL, index, 0);

            if (code != OS.CB_ERR && code != selection)
            {
                sendEvent(SWT.Modify);
            }
        }
    }

    /**
     * TODO CLEAN/COMMENT
     */
    void setBackgroundImage(int /* long */hBitmap)
    {
        super.setBackgroundImage(hBitmap);
        int /* long */hwndText = getEditControl();
        if (hwndText != 0)
            OS.InvalidateRect(hwndText, null, true);
        int /* long */hwndList = getComboControl();
        if (hwndList != 0)
            OS.InvalidateRect(hwndList, null, true);
    }

    /**
     * TODO CLEAN/COMMENT
     */
    void setBackgroundPixel(int pixel)
    {
        super.setBackgroundPixel(pixel);
        int /* long */hwndText = getEditControl();
        if (hwndText != 0)
            OS.InvalidateRect(hwndText, null, true);
        int /* long */hwndList = getComboControl();
        if (hwndList != 0)
            OS.InvalidateRect(hwndList, null, true);
    }

    /**
     * Sets the receiver's size and location to the rectangular
     * area specified by the arguments.
     * 
     * @param x absolute X position
     * @param y absolute Y position
     * @param width Width
     * @param height Height
     * @param flags Flags
     */
    void setBounds(int x, int y, int width, int height, int flags)
    {
        /*
         * Feature in Windows. If the combo box has the CBS_DROPDOWN or
         * CBS_DROPDOWNLIST style, Windows uses the height that the programmer
         * sets in SetWindowPos () to control height of the drop down list. When
         * the width is non-zero, Windows remembers this value and sets the
         * height to be the height of the text field part of the combo box. If
         * the width is zero, Windows allows the height to have any value.
         * Therefore, when the programmer sets and then queries the height, the
         * values can be different depending on the width. The problem occurs
         * when the programmer uses computeSize () to determine the preferred
         * height (always the height of the text field) and then uses this value
         * to set the height of the combo box. The result is a combo box with a
         * zero size drop down list. The fix, is to always set the height to
         * show a fixed number of combo box items and ignore the height value
         * that the programmer supplies.
         */
        if ((style & SWT.DROP_DOWN) != 0)
        {
            height = getTextHeight() + (getItemHeight() * visibleCount) + 2;

            /*
             * Feature in Windows. When a drop down combo box is resized, the
             * combo box resizes the height of the text field and uses the
             * height provided in SetWindowPos () to determine the height of the
             * drop down list. For some reason, the combo box redraws the whole
             * area, not just the text field. The fix is to set the SWP_NOSIZE
             * bits when the height of text field and the drop down list is the
             * same as the requested height.
             * 
             * NOTE: Setting the width of a combo box to zero does not update
             * the width of the drop down control rect. If the width of the
             * combo box is zero, then do not set SWP_NOSIZE.
             */
            RECT rect = new RECT();
            OS.GetWindowRect(handle, rect);

            if (rect.right - rect.left != 0)
            {
                if (OS.SendMessage(handle, OS.CB_GETDROPPEDCONTROLRECT, 0, rect) != 0)
                {
                    int oldWidth = rect.right - rect.left;
                    int oldHeight = rect.bottom - rect.top;

                    if (oldWidth == width && oldHeight == height)
                    {
                        flags |= OS.SWP_NOSIZE;
                    }
                }
            }

            SetWindowPos(handle, 0, x, y, width, height, flags);
        }
        else
        {
            super.setBounds(x, y, width, height, flags);
        }
    }

    /**
     * TODO CLEAN/COMMENT
     */
    public void setFont(Font font)
    {
        checkWidget();
        super.setFont(font);
        if ((style & SWT.H_SCROLL) != 0)
            setScrollWidth();
    }

    /**
     * TODO CLEAN/COMMENT
     */
    void setForegroundPixel(int pixel)
    {
        super.setForegroundPixel(pixel);
        int /* long */hwndText = getEditControl();
        if (hwndText != 0)
            OS.InvalidateRect(hwndText, null, true);
        int /* long */hwndList = getComboControl();
        if (hwndList != 0)
            OS.InvalidateRect(hwndList, null, true);
    }

    /**
     * TODO CLEAN/COMMENT
     */
    /**
     * Sets the text of the item in the receiver's list at the given
     * zero-relative index to the string argument.
     * 
     * @param index the index for the item
     * @param string the new text for the item
     * 
     * @exception IllegalArgumentException <ul>
     *                <li>ERROR_INVALID_RANGE - if the index is not between 0
     *                and the number of elements in the list minus 1 (inclusive)
     *                </li>
     *                <li>ERROR_NULL_ARGUMENT - if the string is null</li>
     *                </ul>
     * @exception SWTException <ul>
     *                <li>ERROR_WIDGET_DISPOSED - if the receiver has been
     *                disposed</li>
     *                <li>ERROR_THREAD_INVALID_ACCESS - if not called from the
     *                thread that created the receiver</li>
     *                </ul>
     */
    public void setItem(int index, Image image, String string)
    {
        checkWidget();
        if (string == null)
            error(SWT.ERROR_NULL_ARGUMENT);
        int selection = getSelectionIndex();
        remove(index, false);
        if (isDisposed())
            return;
        add(string, image, index);
        if (selection != -1)
            select(selection);
    }

    /**
     * TODO CLEAN/COMMENT
     */
    /**
     * Sets the receiver's list to be the given array of items.
     * 
     * @param items the array of items
     * 
     * @exception IllegalArgumentException <ul>
     *                <li>ERROR_NULL_ARGUMENT - if the items array is null</li>
     *                <li>ERROR_INVALID_ARGUMENT - if an item in the items array
     *                is null</li>
     *                </ul>
     * @exception SWTException <ul>
     *                <li>ERROR_WIDGET_DISPOSED - if the receiver has been
     *                disposed</li>
     *                <li>ERROR_THREAD_INVALID_ACCESS - if not called from the
     *                thread that created the receiver</li>
     *                </ul>
     */
    public void setItems(String[] items)
    {
        checkWidget();
        if (items == null)
            error(SWT.ERROR_NULL_ARGUMENT);
        for (int i = 0; i < items.length; i++)
        {
            if (items[i] == null)
                error(SWT.ERROR_INVALID_ARGUMENT);
        }
        RECT rect = null;
        int /* long */hDC = 0, oldFont = 0, newFont = 0;
        int newWidth = 0;
        if ((style & SWT.H_SCROLL) != 0)
        {
            rect = new RECT();
            hDC = OS.GetDC(handle);
            newFont = OS.SendMessage(handle, OS.WM_GETFONT, 0, 0);
            if (newFont != 0)
                oldFont = OS.SelectObject(hDC, newFont);
            setScrollWidth(0);
        }
        OS.SendMessage(handle, OS.CB_RESETCONTENT, 0, 0);
        int codePage = getCodePage();
        for (int i = 0; i < items.length; i++)
        {

            String string = items[i];
            TCHAR buffer = new TCHAR(codePage, string, true);
//            int code = (int) /* 64 */OS.SendMessage(handle, OS.CB_ADDSTRING, 0, buffer);
//            if (code == OS.CB_ERR)
//                error(SWT.ERROR_ITEM_NOT_ADDED);
//            if (code == OS.CB_ERRSPACE)
//                error(SWT.ERROR_ITEM_NOT_ADDED);

            if ((style & SWT.H_SCROLL) != 0)
            {
                int flags = OS.DT_CALCRECT | OS.DT_SINGLELINE | OS.DT_NOPREFIX;
                OS.DrawText(hDC, buffer, -1, rect, flags);
                newWidth = Math.max(newWidth, rect.right - rect.left);
            }
        }
        if ((style & SWT.H_SCROLL) != 0)
        {
            if (newFont != 0)
                OS.SelectObject(hDC, oldFont);
            OS.ReleaseDC(handle, hDC);
            setScrollWidth(newWidth + 3);
        }
        sendEvent(SWT.Modify);
        // widget could be disposed at this point
    }

    /**
     * TODO CLEAN/COMMENT
     */
    /**
     * Sets the orientation of the receiver, which must be one of the constants
     * <code>SWT.LEFT_TO_RIGHT</code> or <code>SWT.RIGHT_TO_LEFT</code>.
     * <p>
     * 
     * @param orientation new orientation style
     * 
     * @exception SWTException <ul>
     *                <li>ERROR_WIDGET_DISPOSED - if the receiver has been
     *                disposed</li> <li>ERROR_THREAD_INVALID_ACCESS - if not
     *                called from the thread that created the receiver</li>
     *                </ul>
     * 
     * @since 2.1.2
     */
    public void setOrientation(int orientation)
    {
        checkWidget();
        if (OS.IsWinCE)
            return;
        if (OS.WIN32_VERSION < OS.VERSION(4, 10))
            return;
        int flags = SWT.RIGHT_TO_LEFT | SWT.LEFT_TO_RIGHT;
        if ((orientation & flags) == 0 || (orientation & flags) == flags)
            return;
        style &= ~flags;
        style |= orientation & flags;
        int bits = OS.GetWindowLong(handle, OS.GWL_EXSTYLE);
        if ((style & SWT.RIGHT_TO_LEFT) != 0)
        {
            style |= SWT.MIRRORED;
            bits |= OS.WS_EX_LAYOUTRTL;
        }
        else
        {
            style &= ~SWT.MIRRORED;
            bits &= ~OS.WS_EX_LAYOUTRTL;
        }
        OS.SetWindowLong(handle, OS.GWL_EXSTYLE, bits);
        int /* long */hwndText = 0, hwndList = 0;
        COMBOBOXINFO pcbi = new COMBOBOXINFO();
        pcbi.cbSize = COMBOBOXINFO.sizeof;
        if (OS.GetComboBoxInfo(handle, pcbi))
        {
            hwndText = pcbi.hwndItem;
            hwndList = pcbi.hwndList;
        }
        if (hwndText != 0)
        {
            int bits1 = OS.GetWindowLong(hwndText, OS.GWL_EXSTYLE);
            int bits2 = OS.GetWindowLong(hwndText, OS.GWL_STYLE);
            if ((style & SWT.RIGHT_TO_LEFT) != 0)
            {
                bits1 |= OS.WS_EX_RIGHT | OS.WS_EX_RTLREADING;
                bits2 |= OS.ES_RIGHT;
            }
            else
            {
                bits1 &= ~(OS.WS_EX_RIGHT | OS.WS_EX_RTLREADING);
                bits2 &= ~OS.ES_RIGHT;
            }
            OS.SetWindowLong(hwndText, OS.GWL_EXSTYLE, bits1);
            OS.SetWindowLong(hwndText, OS.GWL_STYLE, bits2);

            /*
             * Bug in Windows. For some reason, the single line text field
             * portion of the combo box does not redraw to reflect the new style
             * bits. The fix is to force the widget to be resized by temporarily
             * shrinking and then growing the width and height.
             */
            RECT rect = new RECT();
            OS.GetWindowRect(hwndText, rect);
            int width = rect.right - rect.left, height = rect.bottom - rect.top;
            OS.GetWindowRect(handle, rect);
            int widthCombo = rect.right - rect.left, heightCombo = rect.bottom - rect.top;
            int uFlags = OS.SWP_NOMOVE | OS.SWP_NOZORDER | OS.SWP_NOACTIVATE;
            SetWindowPos(hwndText, 0, 0, 0, width - 1, height - 1, uFlags);
            SetWindowPos(handle, 0, 0, 0, widthCombo - 1, heightCombo - 1, uFlags);
            SetWindowPos(hwndText, 0, 0, 0, width, height, uFlags);
            SetWindowPos(handle, 0, 0, 0, widthCombo, heightCombo, uFlags);
            OS.InvalidateRect(handle, null, true);
        }
        if (hwndList != 0)
        {
            int bits1 = OS.GetWindowLong(hwndList, OS.GWL_EXSTYLE);
            if ((style & SWT.RIGHT_TO_LEFT) != 0)
            {
                bits1 |= OS.WS_EX_LAYOUTRTL;
            }
            else
            {
                bits1 &= ~OS.WS_EX_LAYOUTRTL;
            }
            OS.SetWindowLong(hwndList, OS.GWL_EXSTYLE, bits1);
        }
    }

    /**
     * TODO CLEAN/COMMENT
     */
    void setScrollWidth()
    {
        int newWidth = 0;
        RECT rect = new RECT();
        int /* long */newFont, oldFont = 0;
        int /* long */hDC = OS.GetDC(handle);
        newFont = OS.SendMessage(handle, OS.WM_GETFONT, 0, 0);
        if (newFont != 0)
            oldFont = OS.SelectObject(hDC, newFont);
        int cp = getCodePage();
        int count = (int) /* 64 */OS.SendMessage(handle, OS.CB_GETCOUNT, 0, 0);
        int flags = OS.DT_CALCRECT | OS.DT_SINGLELINE | OS.DT_NOPREFIX;
        for (int i = 0; i < count; i++)
        {
            int length = (int) /* 64 */OS.SendMessage(handle, OS.CB_GETLBTEXTLEN, i, 0);
            if (length != OS.CB_ERR)
            {
                TCHAR buffer = new TCHAR(cp, length + 1);
                int result = (int) /* 64 */OS.SendMessage(handle, OS.CB_GETLBTEXT, i, buffer);
                if (result != OS.CB_ERR)
                {
                    OS.DrawText(hDC, buffer, -1, rect, flags);
                    newWidth = Math.max(newWidth, rect.right - rect.left);
                }
            }
        }
        if (newFont != 0)
            OS.SelectObject(hDC, oldFont);
        OS.ReleaseDC(handle, hDC);
        setScrollWidth(newWidth + 3);
    }

    /**
     * TODO CLEAN/COMMENT
     */
    void setScrollWidth(int scrollWidth)
    {
        this.scrollWidth = scrollWidth;
        if ((style & SWT.SIMPLE) != 0)
        {
            OS.SendMessage(handle, OS.CB_SETHORIZONTALEXTENT, scrollWidth, 0);
            return;
        }
        boolean scroll = false;
        int count = (int) /* 64 */OS.SendMessage(handle, OS.CB_GETCOUNT, 0, 0);
        if (count > 3)
        {
            int maxWidth = 0;
            if (OS.IsWinCE || OS.WIN32_VERSION < OS.VERSION(4, 10))
            {
                RECT rect = new RECT();
                OS.SystemParametersInfo(OS.SPI_GETWORKAREA, 0, rect, 0);
                maxWidth = (rect.right - rect.left) / 4;
            }
            else
            {
                int /* long */hmonitor = OS.MonitorFromWindow(handle, OS.MONITOR_DEFAULTTONEAREST);
                MONITORINFO lpmi = new MONITORINFO();
                lpmi.cbSize = MONITORINFO.sizeof;
                OS.GetMonitorInfo(hmonitor, lpmi);
                maxWidth = (lpmi.rcWork_right - lpmi.rcWork_left) / 4;
            }
            scroll = scrollWidth > maxWidth;
        }
        if (scroll)
        {
            OS.SendMessage(handle, OS.CB_SETDROPPEDWIDTH, 0, 0);
            OS.SendMessage(handle, OS.CB_SETHORIZONTALEXTENT, scrollWidth, 0);
        }
        else
        {
            scrollWidth += OS.GetSystemMetrics(OS.SM_CYHSCROLL);
            OS.SendMessage(handle, OS.CB_SETDROPPEDWIDTH, scrollWidth, 0);
            OS.SendMessage(handle, OS.CB_SETHORIZONTALEXTENT, 0, 0);
        }
    }

    /**
     * TODO CLEAN/COMMENT
     */
    void setScrollWidth(TCHAR buffer, boolean grow)
    {
        RECT rect = new RECT();
        int /* long */newFont, oldFont = 0;
        int /* long */hDC = OS.GetDC(handle);
        newFont = OS.SendMessage(handle, OS.WM_GETFONT, 0, 0);
        if (newFont != 0)
            oldFont = OS.SelectObject(hDC, newFont);
        int flags = OS.DT_CALCRECT | OS.DT_SINGLELINE | OS.DT_NOPREFIX;
        OS.DrawText(hDC, buffer, -1, rect, flags);
        if (newFont != 0)
            OS.SelectObject(hDC, oldFont);
        OS.ReleaseDC(handle, hDC);
        setScrollWidth(rect.right - rect.left, grow);
    }

    /**
     * TODO CLEAN/COMMENT
     */
    void setScrollWidth(int newWidth, boolean grow)
    {
        if (grow)
        {
            if (newWidth <= scrollWidth)
                return;
            setScrollWidth(newWidth + 3);
        }
        else
        {
            if (newWidth < scrollWidth)
                return;
            setScrollWidth();
        }
    }

    /**
     * TODO CLEAN/COMMENT
     */
    /**
     * Sets the selection in the receiver's text field to the range specified by
     * the argument whose x coordinate is the start of the selection and whose y
     * coordinate is the end of the selection.
     * 
     * @param selection a point representing the new selection start and end
     * 
     * @exception IllegalArgumentException <ul>
     *                <li>ERROR_NULL_ARGUMENT - if the point is null</li>
     *                </ul>
     * @exception SWTException <ul>
     *                <li>ERROR_WIDGET_DISPOSED - if the receiver has been
     *                disposed</li>
     *                <li>ERROR_THREAD_INVALID_ACCESS - if not called from the
     *                thread that created the receiver</li>
     *                </ul>
     */
    public void setSelection(Point selection)
    {
        checkWidget();
        if (selection == null)
            error(SWT.ERROR_NULL_ARGUMENT);
        int start = selection.x, end = selection.y;
        if (!OS.IsUnicode && OS.IsDBLocale)
        {
            start = wcsToMbcsPos(start);
            end = wcsToMbcsPos(end);
        }
        int /* long */bits = OS.MAKELPARAM(start, end);
        OS.SendMessage(handle, OS.CB_SETEDITSEL, 0, bits);
    }

    /**
     * TODO CLEAN/COMMENT
     */
    /**
     * Sets the contents of the receiver's text field to the given string.
     * <p>
     * Note: The text field in a <code>Combo</code> is typically only capable of
     * displaying a single line of text. Thus, setting the text to a string
     * containing line breaks or other special characters will probably cause it
     * to display incorrectly.
     * </p>
     * 
     * @param string the new text
     * 
     * @exception IllegalArgumentException <ul>
     *                <li>ERROR_NULL_ARGUMENT - if the string is null</li>
     *                </ul>
     * @exception SWTException <ul>
     *                <li>ERROR_WIDGET_DISPOSED - if the receiver has been
     *                disposed</li>
     *                <li>ERROR_THREAD_INVALID_ACCESS - if not called from the
     *                thread that created the receiver</li>
     *                </ul>
     */
    public void setText(String string)
    {
        checkWidget();
        if (string == null)
            error(SWT.ERROR_NULL_ARGUMENT);
        if ((style & SWT.READ_ONLY) != 0)
        {
            int index = indexOf(string);
            if (index != -1)
                select(index);
            return;
        }
        int limit = LIMIT;
        int /* long */hwndText = getEditControl();
        if (hwndText != 0)
        {
            limit = (int) /* 64 */OS.SendMessage(hwndText, OS.EM_GETLIMITTEXT, 0, 0) & 0x7FFFFFFF;
        }
        if (string.length() > limit)
            string = string.substring(0, limit);
        TCHAR buffer = new TCHAR(getCodePage(), string, true);
        if (OS.SetWindowText(handle, buffer))
        {
            sendEvent(SWT.Modify);
            // widget could be disposed at this point
        }
    }

    /**
     * TODO CLEAN/COMMENT
     */
    /**
     * Sets the maximum number of characters that the receiver's text field is
     * capable of holding to be the argument.
     * <p>
     * To reset this value to the default, use
     * <code>setTextLimit(Combo.LIMIT)</code>. Specifying a limit value larger
     * than <code>Combo.LIMIT</code> sets the receiver's limit to
     * <code>Combo.LIMIT</code>.
     * </p>
     * @param limit new text limit
     * 
     * @exception IllegalArgumentException <ul>
     *                <li>ERROR_CANNOT_BE_ZERO - if the limit is zero</li>
     *                </ul>
     * @exception SWTException <ul>
     *                <li>ERROR_WIDGET_DISPOSED - if the receiver has been
     *                disposed</li>
     *                <li>ERROR_THREAD_INVALID_ACCESS - if not called from the
     *                thread that created the receiver</li>
     *                </ul>
     * 
     * @see #LIMIT
     */
    public void setTextLimit(int limit)
    {
        checkWidget();
        if (limit == 0)
            error(SWT.ERROR_CANNOT_BE_ZERO);
        OS.SendMessage(handle, OS.CB_LIMITTEXT, limit, 0);
    }

    /**
     * TODO CLEAN/COMMENT
     */
    void setToolTipText(Shell shell, String string)
    {
        int /* long */hwndText = getEditControl();
        int /* long */hwndList = getComboControl();
        if (hwndText != 0)
            shell.setToolTipText(hwndText, string);
        if (hwndList != 0)
            shell.setToolTipText(hwndList, string);
        shell.setToolTipText(handle, string);
    }

    /**
     * TODO CLEAN/COMMENT
     */
    /**
     * Sets the number of items that are visible in the drop down portion of the
     * receiver's list.
     * <p>
     * Note: This operation is a hint and is not supported on platforms that do
     * not have this concept.
     * </p>
     * 
     * @param count the new number of items to be visible
     * 
     * @exception SWTException <ul>
     *                <li>ERROR_WIDGET_DISPOSED - if the receiver has been
     *                disposed</li>
     *                <li>ERROR_THREAD_INVALID_ACCESS - if not called from the
     *                thread that created the receiver</li>
     *                </ul>
     * 
     * @since 3.0
     */
    public void setVisibleItemCount(int count)
    {
        checkWidget();
        if (count < 0)
            return;
        visibleCount = count;
        if ((style & SWT.DROP_DOWN) != 0)
        {
            forceResize();
            RECT rect = new RECT();
            OS.GetWindowRect(handle, rect);
            int flags = OS.SWP_NOMOVE | OS.SWP_NOZORDER | OS.SWP_DRAWFRAME | OS.SWP_NOACTIVATE;
            setBounds(0, 0, rect.right - rect.left, rect.bottom - rect.top, flags);
        }
    }

    /**
     * TODO CLEAN/COMMENT
     */
    void subclass()
    {
        super.subclass();
        int /* long */newProc = display.windowProc;
        int /* long */hwndText = getEditControl();
        if (hwndText != 0)
        {
            OS.SetWindowLongPtr(hwndText, OS.GWLP_WNDPROC, newProc);
        }
        int /* long */hwndList = getComboControl();
        if (hwndList != 0)
        {
            OS.SetWindowLongPtr(hwndList, OS.GWLP_WNDPROC, newProc);
        }
    }

    /**
     * TODO CLEAN/COMMENT
     */
    boolean translateTraversal(MSG msg)
    {
        /*
         * When the combo box is dropped down, allow return to select an item in
         * the list and escape to close the combo box.
         */
        switch ((int) /* 64 */(msg.wParam))
        {
            case OS.VK_RETURN:
            case OS.VK_ESCAPE:
                if ((style & SWT.DROP_DOWN) != 0)
                {
                    if (OS.SendMessage(handle, OS.CB_GETDROPPEDSTATE, 0, 0) != 0)
                    {
                        return false;
                    }
                }
        }
        return super.translateTraversal(msg);
    }

    /**
     * TODO CLEAN/COMMENT
     */
    boolean traverseEscape()
    {
        if ((style & SWT.DROP_DOWN) != 0)
        {
            if (OS.SendMessage(handle, OS.CB_GETDROPPEDSTATE, 0, 0) != 0)
            {
                OS.SendMessage(handle, OS.CB_SHOWDROPDOWN, 0, 0);
                return true;
            }
        }
        return super.traverseEscape();
    }

    /**
     * TODO CLEAN/COMMENT
     */
    boolean traverseReturn()
    {
        if ((style & SWT.DROP_DOWN) != 0)
        {
            if (OS.SendMessage(handle, OS.CB_GETDROPPEDSTATE, 0, 0) != 0)
            {
                OS.SendMessage(handle, OS.CB_SHOWDROPDOWN, 0, 0);
                return true;
            }
        }
        return super.traverseReturn();
    }

    /**
     * TODO CLEAN/COMMENT
     */
    void unsubclass()
    {
        super.unsubclass();
        int /* long */hwndText = getEditControl();
        if (hwndText != 0 && EditProc != 0)
        {
            OS.SetWindowLongPtr(hwndText, OS.GWLP_WNDPROC, EditProc);
        }
        int /* long */hwndList = getComboControl();
        if (hwndList != 0 && ListProc != 0)
        {
            OS.SetWindowLongPtr(hwndList, OS.GWLP_WNDPROC, ListProc);
        }
    }

    /**
     * TODO CLEAN/COMMENT
     */
    String verifyText(String string, int start, int end, Event keyEvent)
    {
        Event event = new Event();
        event.text = string;
        event.start = start;
        event.end = end;
        if (keyEvent != null)
        {
            event.character = keyEvent.character;
            event.keyCode = keyEvent.keyCode;
            event.stateMask = keyEvent.stateMask;
        }
        if (!OS.IsUnicode && OS.IsDBLocale)
        {
            event.start = mbcsToWcsPos(start);
            event.end = mbcsToWcsPos(end);
        }
        /*
         * It is possible (but unlikely), that application code could have
         * disposed the widget in the verify event. If this happens, answer null
         * to cancel the operation.
         */
        sendEvent(SWT.Verify, event);
        if (!event.doit || isDisposed())
            return null;
        return event.text;
    }

    /**
     * TODO CLEAN/COMMENT
     */
    int wcsToMbcsPos(int wcsPos)
    {
        if (wcsPos <= 0)
            return 0;
        if (OS.IsUnicode)
            return wcsPos;
        int /* long */hwndText = getEditControl();
        if (hwndText == 0)
            return wcsPos;
        int mbcsSize = OS.GetWindowTextLengthA(hwndText);
        if (mbcsSize == 0)
            return 0;
        byte[] buffer = new byte[mbcsSize + 1];
        OS.GetWindowTextA(hwndText, buffer, mbcsSize + 1);
        int mbcsPos = 0, wcsCount = 0;
        while (mbcsPos < mbcsSize)
        {
            if (wcsPos == wcsCount)
                break;
            if (OS.IsDBCSLeadByte(buffer[mbcsPos++]))
                mbcsPos++;
            wcsCount++;
        }
        return mbcsPos;
    }

    /**
     * Returns extended style that will be provided to CreateWindowEx.
     * 
     * @return Extended style
     */
    int widgetExtStyle()
    {
        return super.widgetExtStyle() & ~OS.WS_EX_NOINHERITLAYOUT;
    }

    /**
     * Translate SWT styles into OS dependent styles that will be
     * provided to CreateWindowEx.
     * 
     * @return Translated style
     */
    int widgetStyle()
    {
        int bits = super.widgetStyle();

        bits |= OS.CBS_AUTOHSCROLL;

        if ((style & SWT.SIMPLE) != 0)
        {
            return bits | OS.CBS_SIMPLE;
        }
        if ((style & SWT.READ_ONLY) != 0)
        {
            return bits | OS.CBS_DROPDOWNLIST;
        }

        return bits | OS.CBS_DROPDOWN;
    }

    /**
     * TODO CLEAN/COMMENT
     */
    TCHAR windowClass()
    {
        return ComboBoxExClass;
    }

    /**
     * TODO CLEAN/COMMENT
     */
    int /* long */windowProc()
    {
        return ComboBoxProc;
    }

    /**
     * TODO CLEAN/COMMENT
     */
    int /* long */windowProc(int /* long */hwnd, int msg, int /* long */wParam,
        int /* long */lParam)
    {
        if (handle == 0)
            return 0;
        if (hwnd != handle)
        {
            int /* long */hwndText = getEditControl();
            int /* long */hwndList = getComboControl();
            if ((hwndText != 0 && hwnd == hwndText) || (hwndList != 0 && hwnd == hwndList))
            {
                LRESULT result = null;
                switch (msg)
                {
                    /* Keyboard messages */
                    case OS.WM_CHAR:
                        result = wmChar(hwnd, wParam, lParam);
                        break;
                    case OS.WM_IME_CHAR:
                        result = wmIMEChar(hwnd, wParam, lParam);
                        break;
                    case OS.WM_KEYDOWN:
                        result = wmKeyDown(hwnd, wParam, lParam);
                        break;
                    case OS.WM_KEYUP:
                        result = wmKeyUp(hwnd, wParam, lParam);
                        break;
                    case OS.WM_SYSCHAR:
                        result = wmSysChar(hwnd, wParam, lParam);
                        break;
                    case OS.WM_SYSKEYDOWN:
                        result = wmSysKeyDown(hwnd, wParam, lParam);
                        break;
                    case OS.WM_SYSKEYUP:
                        result = wmSysKeyUp(hwnd, wParam, lParam);
                        break;

                    /* Mouse Messages */
                    case OS.WM_CAPTURECHANGED:
                        result = wmCaptureChanged(hwnd, wParam, lParam);
                        break;
                    case OS.WM_LBUTTONDBLCLK:
                        result = wmLButtonDblClk(hwnd, wParam, lParam);
                        break;
                    case OS.WM_LBUTTONDOWN:
                        result = wmLButtonDown(hwnd, wParam, lParam);
                        break;
                    case OS.WM_LBUTTONUP:
                        result = wmLButtonUp(hwnd, wParam, lParam);
                        break;
                    case OS.WM_MBUTTONDBLCLK:
                        result = wmMButtonDblClk(hwnd, wParam, lParam);
                        break;
                    case OS.WM_MBUTTONDOWN:
                        result = wmMButtonDown(hwnd, wParam, lParam);
                        break;
                    case OS.WM_MBUTTONUP:
                        result = wmMButtonUp(hwnd, wParam, lParam);
                        break;
                    case OS.WM_MOUSEHOVER:
                        result = wmMouseHover(hwnd, wParam, lParam);
                        break;
                    case OS.WM_MOUSELEAVE:
                        result = wmMouseLeave(hwnd, wParam, lParam);
                        break;
                    case OS.WM_MOUSEMOVE:
                        result = wmMouseMove(hwnd, wParam, lParam);
                        break;
                    // case OS.WM_MOUSEWHEEL: result = wmMouseWheel (hwnd,
                    // wParam, lParam); break;
                    case OS.WM_RBUTTONDBLCLK:
                        result = wmRButtonDblClk(hwnd, wParam, lParam);
                        break;
                    case OS.WM_RBUTTONDOWN:
                        result = wmRButtonDown(hwnd, wParam, lParam);
                        break;
                    case OS.WM_RBUTTONUP:
                        result = wmRButtonUp(hwnd, wParam, lParam);
                        break;
                    case OS.WM_XBUTTONDBLCLK:
                        result = wmXButtonDblClk(hwnd, wParam, lParam);
                        break;
                    case OS.WM_XBUTTONDOWN:
                        result = wmXButtonDown(hwnd, wParam, lParam);
                        break;
                    case OS.WM_XBUTTONUP:
                        result = wmXButtonUp(hwnd, wParam, lParam);
                        break;

                    /* Paint messages */
                    case OS.WM_PAINT:
                        result = wmPaint(hwnd, wParam, lParam);
                        break;

                    /* Menu messages */
                    case OS.WM_CONTEXTMENU:
                        result = wmContextMenu(hwnd, wParam, lParam);
                        break;

                    /* Clipboard messages */
                    case OS.WM_CLEAR:
                    case OS.WM_CUT:
                    case OS.WM_PASTE:
                    case OS.WM_UNDO:
                    case OS.EM_UNDO:
                    case OS.WM_SETTEXT:
                        if (hwnd == hwndText)
                        {
                            result = wmClipboard(hwnd, msg, wParam, lParam);
                        }
                        break;
                }
                if (result != null)
                    return result.value;
                return callWindowProc(hwnd, msg, wParam, lParam);
            }
        }
        if (msg == OS.CB_SETCURSEL)
        {
            if ((style & SWT.READ_ONLY) != 0)
            {
                if (hooks(SWT.Verify) || filters(SWT.Verify))
                {
                    String oldText = getText(), newText = null;
                    if (wParam == -1)
                    {
                        newText = "";
                    }
                    else
                    {
                        if (0 <= wParam && wParam < getItemCount())
                        {
                            newText = getItem((int) /* 64 */wParam);
                        }
                    }
                    if (newText != null && !newText.equals(oldText))
                    {
                        int length = OS.GetWindowTextLength(handle);
                        oldText = newText;
                        newText = verifyText(newText, 0, length, null);
                        if (newText == null)
                            return 0;
                        if (!newText.equals(oldText))
                        {
                            int index = indexOf(newText);
                            if (index != -1 && index != wParam)
                            {
                                return callWindowProc(handle, OS.CB_SETCURSEL, index, lParam);
                            }
                        }
                    }
                }
            }
        }
        return super.windowProc(hwnd, msg, wParam, lParam);
    }

    /**
     * TODO CLEAN/COMMENT
     */
    LRESULT WM_CTLCOLOR(int /* long */wParam, int /* long */lParam)
    {
        return wmColorChild(wParam, lParam);
    }

    /**
     * TODO CLEAN/COMMENT
     */
    LRESULT WM_GETDLGCODE(int /* long */wParam, int /* long */lParam)
    {
        int /* long */code = callWindowProc(handle, OS.WM_GETDLGCODE, wParam, lParam);
        return new LRESULT(code | OS.DLGC_WANTARROWS);
    }

    /**
     * TODO CLEAN/COMMENT
     */
    LRESULT WM_KILLFOCUS(int /* long */wParam, int /* long */lParam)
    {
        /*
         * Bug in Windows. When a combo box that is read only is disposed in
         * CBN_KILLFOCUS, Windows segment faults. The fix is to send focus from
         * WM_KILLFOCUS instead of CBN_KILLFOCUS.
         * 
         * NOTE: In version 6 of COMCTL32.DLL, the bug is fixed.
         */
        if ((style & SWT.READ_ONLY) != 0)
        {
            return super.WM_KILLFOCUS(wParam, lParam);
        }

        /*
         * Return NULL - Focus notification is done in WM_COMMAND by
         * CBN_KILLFOCUS.
         */
        return null;
    }

    /**
     * TODO CLEAN/COMMENT
     */
    LRESULT WM_LBUTTONDOWN(int /* long */wParam, int /* long */lParam)
    {
        /*
         * Feature in Windows. When an editable combo box is dropped down and
         * the text in the entry field partially matches an item in the list,
         * Windows selects the item but doesn't send WM_COMMAND with
         * CBN_SELCHANGE. The fix is to detect that the selection has changed
         * and issue the notification.
         */
        int oldSelection = (int) /* 64 */OS.SendMessage(handle, OS.CB_GETCURSEL, 0, 0);
        LRESULT result = super.WM_LBUTTONDOWN(wParam, lParam);
        if (result == LRESULT.ZERO)
            return result;
        if ((style & SWT.READ_ONLY) == 0)
        {
            int newSelection = (int) /* 64 */OS.SendMessage(handle, OS.CB_GETCURSEL, 0, 0);
            if (oldSelection != newSelection)
            {
                sendEvent(SWT.Modify);
                if (isDisposed())
                    return LRESULT.ZERO;
                sendEvent(SWT.Selection);
                if (isDisposed())
                    return LRESULT.ZERO;
            }
        }
        return result;
    }

    /**
     * TODO CLEAN/COMMENT
     */
    LRESULT WM_SETFOCUS(int /* long */wParam, int /* long */lParam)
    {
        /*
         * Return NULL - Focus notification is done by WM_COMMAND with
         * CBN_SETFOCUS.
         */
        return null;
    }

    /**
     * TODO CLEAN/COMMENT
     */
    LRESULT WM_SIZE(int /* long */wParam, int /* long */lParam)
    {
        /*
         * Feature in Windows. When a combo box is resized, the size of the drop
         * down rectangle is specified using the height and then the combo box
         * resizes to be the height of the text field. This causes two WM_SIZE
         * messages to be sent and two SWT.Resize events to be issued. The fix
         * is to ignore the second resize.
         */
        if (ignoreResize)
            return null;
        /*
         * Bug in Windows. If the combo box has the CBS_SIMPLE style, the list
         * portion of the combo box is not redrawn when the combo box is
         * resized. The fix is to force a redraw when the size has changed.
         */
        if ((style & SWT.SIMPLE) != 0)
        {
            LRESULT result = super.WM_SIZE(wParam, lParam);
            if (OS.IsWindowVisible(handle))
            {
                if (OS.IsWinCE)
                {
                    int /* long */hwndText = getEditControl();
                    if (hwndText != 0)
                        OS.InvalidateRect(hwndText, null, true);
                    int /* long */hwndList = getComboControl();
                    if (hwndList != 0)
                        OS.InvalidateRect(hwndList, null, true);
                }
                else
                {
                    int uFlags = OS.RDW_ERASE | OS.RDW_INVALIDATE | OS.RDW_ALLCHILDREN;
                    OS.RedrawWindow(handle, null, 0, uFlags);
                }
            }
            return result;
        }

        /*
         * Feature in Windows. When an editable drop down combo box contains
         * text that does not correspond to an item in the list, when the widget
         * is resized, it selects the closest match from the list. The fix is to
         * remember the original text and reset it after the widget is resized.
         */
        LRESULT result = null;
        if ((style & SWT.READ_ONLY) != 0)
        {
            result = super.WM_SIZE(wParam, lParam);
        }
        else
        {
            int index = (int) /* 64 */OS.SendMessage(handle, OS.CB_GETCURSEL, 0, 0);
            boolean redraw = false;
            TCHAR buffer = null;
            int[] start = null, end = null;
            if (index == OS.CB_ERR)
            {
                int length = OS.GetWindowTextLength(handle);
                if (length != 0)
                {
                    buffer = new TCHAR(getCodePage(), length + 1);
                    OS.GetWindowText(handle, buffer, length + 1);
                    start = new int[1];
                    end = new int[1];
                    OS.SendMessage(handle, OS.CB_GETEDITSEL, start, end);
                    redraw = drawCount == 0 && OS.IsWindowVisible(handle);
                    if (redraw)
                        setRedraw(false);
                }
            }
            result = super.WM_SIZE(wParam, lParam);
            /*
             * It is possible (but unlikely), that application code could have
             * disposed the widget in the resize event. If this happens, end the
             * processing of the Windows message by returning the result of the
             * WM_SIZE message.
             */
            if (isDisposed())
                return result;
            if (buffer != null)
            {
                OS.SetWindowText(handle, buffer);
                int /* long */bits = OS.MAKELPARAM(start[0], end[0]);
                OS.SendMessage(handle, OS.CB_SETEDITSEL, 0, bits);
                if (redraw)
                    setRedraw(true);
            }
        }
        /*
         * Feature in Windows. When CB_SETDROPPEDWIDTH is called with a width
         * that is smaller than the current size of the combo box, it is
         * ignored. This the fix is to set the width after the combo box has
         * been resized.
         */
        if ((style & SWT.H_SCROLL) != 0)
            setScrollWidth(scrollWidth);
        return result;
    }

    /**
     * TODO CLEAN/COMMENT
     */
    LRESULT WM_WINDOWPOSCHANGING(int /* long */wParam, int /* long */lParam)
    {
        LRESULT result = super.WM_WINDOWPOSCHANGING(wParam, lParam);
        if (result != null)
            return result;
        /*
         * Feature in Windows. When a combo box is resized, the size of the drop
         * down rectangle is specified using the height and then the combo box
         * resizes to be the height of the text field. This causes sibling
         * windows that intersect with the original bounds to redrawn. The fix
         * is to stop the redraw using SWP_NOREDRAW and then damage the combo
         * box text field and the area in the parent where the combo box used to
         * be.
         */
        if (OS.IsWinCE)
            return result;
        if (drawCount != 0)
            return result;
        if (!OS.IsWindowVisible(handle))
            return result;
        if (ignoreResize)
        {
            WINDOWPOS lpwp = new WINDOWPOS();
            OS.MoveMemory(lpwp, lParam, WINDOWPOS.sizeof);
            if ((lpwp.flags & OS.SWP_NOSIZE) == 0)
            {
                lpwp.flags |= OS.SWP_NOREDRAW;
                OS.MoveMemory(lParam, lpwp, WINDOWPOS.sizeof);
                OS.InvalidateRect(handle, null, true);
                RECT rect = new RECT();
                OS.GetWindowRect(handle, rect);
                int width = rect.right - rect.left;
                int height = rect.bottom - rect.top;
                if (width != 0 && height != 0)
                {
                    int /* long */hwndParent = parent.handle;
                    int /* long */hwndChild = OS.GetWindow(hwndParent, OS.GW_CHILD);
                    OS.MapWindowPoints(0, hwndParent, rect, 2);
                    int /* long */rgn1 =
                        OS.CreateRectRgn(rect.left, rect.top, rect.right, rect.bottom);
                    while (hwndChild != 0)
                    {
                        if (hwndChild != handle)
                        {
                            OS.GetWindowRect(hwndChild, rect);
                            OS.MapWindowPoints(0, hwndParent, rect, 2);
                            int /* long */rgn2 =
                                OS.CreateRectRgn(rect.left, rect.top, rect.right, rect.bottom);
                            OS.CombineRgn(rgn1, rgn1, rgn2, OS.RGN_DIFF);
                            OS.DeleteObject(rgn2);
                        }
                        hwndChild = OS.GetWindow(hwndChild, OS.GW_HWNDNEXT);
                    }
                    int flags = OS.RDW_ERASE | OS.RDW_FRAME | OS.RDW_INVALIDATE;
                    OS.RedrawWindow(hwndParent, null, rgn1, flags);
                    OS.DeleteObject(rgn1);
                }
            }
        }
        return result;
    }

    /**
     * TODO CLEAN/COMMENT
     */
    LRESULT wmChar(int /* long */hwnd, int /* long */wParam, int /* long */lParam)
    {
        if (ignoreCharacter)
            return null;
        LRESULT result = super.wmChar(hwnd, wParam, lParam);
        if (result != null)
            return result;
        /*
         * Feature in Windows. For some reason, when the widget is a single line
         * text widget, when the user presses tab, return or escape, Windows
         * beeps. The fix is to look for these keys and not call the window
         * proc.
         * 
         * NOTE: This only happens when the drop down list is not visible.
         */
        switch ((int) /* 64 */wParam)
        {
            case SWT.TAB:
                return LRESULT.ZERO;
            case SWT.CR:
                if (!ignoreDefaultSelection)
                    postEvent(SWT.DefaultSelection);
                ignoreDefaultSelection = false;
                // FALL THROUGH
            case SWT.ESC:
                if ((style & SWT.DROP_DOWN) != 0)
                {
                    if (OS.SendMessage(handle, OS.CB_GETDROPPEDSTATE, 0, 0) == 0)
                    {
                        return LRESULT.ZERO;
                    }
                }
        }
        return result;
    }

    /**
     * TODO CLEAN/COMMENT
     */
    LRESULT wmClipboard(int /* long */hwndText, int msg, int /* long */wParam, int /* long */lParam)
    {
        if ((style & SWT.READ_ONLY) != 0)
            return null;
        if (!hooks(SWT.Verify) && !filters(SWT.Verify))
            return null;
        boolean call = false;
        int[] start = new int[1], end = new int[1];
        String newText = null;
        switch (msg)
        {
            case OS.WM_CLEAR:
            case OS.WM_CUT:
                OS.SendMessage(hwndText, OS.EM_GETSEL, start, end);
                if (start[0] != end[0])
                {
                    newText = "";
                    call = true;
                }
                break;
            case OS.WM_PASTE:
                OS.SendMessage(hwndText, OS.EM_GETSEL, start, end);
                newText = getClipboardText();
                break;
            case OS.EM_UNDO:
            case OS.WM_UNDO:
                if (OS.SendMessage(hwndText, OS.EM_CANUNDO, 0, 0) != 0)
                {
                    ignoreModify = true;
                    OS.CallWindowProc(EditProc, hwndText, msg, wParam, lParam);
                    int length = OS.GetWindowTextLength(hwndText);
                    int[] newStart = new int[1], newEnd = new int[1];
                    OS.SendMessage(hwndText, OS.EM_GETSEL, newStart, newEnd);
                    if (length != 0 && newStart[0] != newEnd[0])
                    {
                        TCHAR buffer = new TCHAR(getCodePage(), length + 1);
                        OS.GetWindowText(hwndText, buffer, length + 1);
                        newText = buffer.toString(newStart[0], newEnd[0] - newStart[0]);
                    }
                    else
                    {
                        newText = "";
                    }
                    OS.CallWindowProc(EditProc, hwndText, msg, wParam, lParam);
                    OS.SendMessage(hwndText, OS.EM_GETSEL, start, end);
                    ignoreModify = false;
                }
                break;
            case OS.WM_SETTEXT:
                end[0] = OS.GetWindowTextLength(hwndText);
                int length = OS.IsUnicode ? OS.wcslen(lParam) : OS.strlen(lParam);
                TCHAR buffer = new TCHAR(getCodePage(), length);
                int byteCount = buffer.length() * TCHAR.sizeof;
                OS.MoveMemory(buffer, lParam, byteCount);
                newText = buffer.toString(0, length);
                break;
        }
        if (newText != null)
        {
            String oldText = newText;
            newText = verifyText(newText, start[0], end[0], null);
            if (newText == null)
                return LRESULT.ZERO;
            if (!newText.equals(oldText))
            {
                if (call)
                {
                    OS.CallWindowProc(EditProc, hwndText, msg, wParam, lParam);
                }
                TCHAR buffer = new TCHAR(getCodePage(), newText, true);
                if (msg == OS.WM_SETTEXT)
                {
                    int /* long */hHeap = OS.GetProcessHeap();
                    int byteCount = buffer.length() * TCHAR.sizeof;
                    int /* long */pszText = OS.HeapAlloc(hHeap, OS.HEAP_ZERO_MEMORY, byteCount);
                    OS.MoveMemory(pszText, buffer, byteCount);
                    int /* long */code =
                        OS.CallWindowProc(EditProc, hwndText, msg, wParam, pszText);
                    OS.HeapFree(hHeap, 0, pszText);
                    return new LRESULT(code);
                }
                else
                {
                    OS.SendMessage(hwndText, OS.EM_REPLACESEL, 0, buffer);
                    return LRESULT.ZERO;
                }
            }
        }
        return null;
    }

    /**
     * TODO CLEAN/COMMENT
     */
    LRESULT wmCommandChild(int /* long */wParam, int /* long */lParam)
    {
        int code = OS.HIWORD(wParam);
        switch (code)
        {
            case OS.CBN_EDITCHANGE:
                if (ignoreModify)
                    break;
                /*
                 * Feature in Windows. If the combo box list selection is
                 * queried using CB_GETCURSEL before the WM_COMMAND (with
                 * CBN_EDITCHANGE) returns, CB_GETCURSEL returns the previous
                 * selection in the list. It seems that the combo box sends the
                 * WM_COMMAND before it makes the selection in the list box
                 * match the entry field. The fix is remember that no selection
                 * in the list should exist in this case.
                 */
                noSelection = true;
                sendEvent(SWT.Modify);
                if (isDisposed())
                    return LRESULT.ZERO;
                noSelection = false;
                break;
            case OS.CBN_SELCHANGE:
                /*
                 * Feature in Windows. If the text in an editable combo box is
                 * queried using GetWindowText () before the WM_COMMAND (with
                 * CBN_SELCHANGE) returns, GetWindowText () returns is the
                 * previous text in the combo box. It seems that the combo box
                 * sends the WM_COMMAND before it updates the text field to
                 * match the list selection. The fix is to force the text field
                 * to match the list selection by re-selecting the list item.
                 */
                int index = (int) /* 64 */OS.SendMessage(handle, OS.CB_GETCURSEL, 0, 0);
                if (index != OS.CB_ERR)
                {
                    OS.SendMessage(handle, OS.CB_SETCURSEL, index, 0);
                }
                /*
                 * It is possible (but unlikely), that application code could
                 * have disposed the widget in the modify event. If this
                 * happens, end the processing of the Windows message by
                 * returning zero as the result of the window proc.
                 */
                sendEvent(SWT.Modify);
                if (isDisposed())
                    return LRESULT.ZERO;
                postEvent(SWT.Selection);
                break;
            case OS.CBN_SETFOCUS:
                sendFocusEvent(SWT.FocusIn);
                if (isDisposed())
                    return LRESULT.ZERO;
                break;
            case OS.CBN_KILLFOCUS:
                /*
                 * Bug in Windows. When a combo box that is read only is
                 * disposed in CBN_KILLFOCUS, Windows segment faults. The fix is
                 * to send focus from WM_KILLFOCUS instead of CBN_KILLFOCUS.
                 * 
                 * NOTE: In version 6 of COMCTL32.DLL, the bug is fixed.
                 */
                if ((style & SWT.READ_ONLY) != 0)
                    break;
                sendFocusEvent(SWT.FocusOut);
                if (isDisposed())
                    return LRESULT.ZERO;
                break;
        }
        return super.wmCommandChild(wParam, lParam);
    }

    /**
     * TODO CLEAN/COMMENT
     */
    LRESULT wmIMEChar(int /* long */hwnd, int /* long */wParam, int /* long */lParam)
    {

        /* Process a DBCS character */
        Display display = this.display;
        display.lastKey = 0;
        display.lastAscii = (int) /* 64 */wParam;
        display.lastVirtual = display.lastNull = display.lastDead = false;
        if (!sendKeyEvent(SWT.KeyDown, OS.WM_IME_CHAR, wParam, lParam))
        {
            return LRESULT.ZERO;
        }

        /*
         * Feature in Windows. The Windows text widget uses two 2 WM_CHAR's to
         * process a DBCS key instead of using WM_IME_CHAR. The fix is to allow
         * the text widget to get the WM_CHAR's but ignore sending them to the
         * application.
         */
        ignoreCharacter = true;
        int /* long */result = callWindowProc(hwnd, OS.WM_IME_CHAR, wParam, lParam);
        MSG msg = new MSG();
        int flags = OS.PM_REMOVE | OS.PM_NOYIELD | OS.PM_QS_INPUT | OS.PM_QS_POSTMESSAGE;
        while (OS.PeekMessage(msg, hwnd, OS.WM_CHAR, OS.WM_CHAR, flags))
        {
            OS.TranslateMessage(msg);
            OS.DispatchMessage(msg);
        }
        ignoreCharacter = false;

        sendKeyEvent(SWT.KeyUp, OS.WM_IME_CHAR, wParam, lParam);
        // widget could be disposed at this point
        display.lastKey = display.lastAscii = 0;
        return new LRESULT(result);
    }

    /**
     * TODO CLEAN/COMMENT
     */
    LRESULT wmKeyDown(int /* long */hwnd, int /* long */wParam, int /* long */lParam)
    {
        if (ignoreCharacter)
            return null;
        LRESULT result = super.wmKeyDown(hwnd, wParam, lParam);
        if (result != null)
            return result;
        ignoreDefaultSelection = false;
        if (wParam == OS.VK_RETURN)
        {
            if ((style & SWT.DROP_DOWN) != 0)
            {
                if (OS.SendMessage(handle, OS.CB_GETDROPPEDSTATE, 0, 0) != 0)
                {
                    ignoreDefaultSelection = true;
                }
            }
        }
        return result;
    }

    /**
     * TODO CLEAN/COMMENT
     */
    LRESULT wmSysKeyDown(int /* long */hwnd, int /* long */wParam, int /* long */lParam)
    {
        /*
         * Feature in Windows. When an editable combo box is dropped down using
         * Alt+Down and the text in the entry field partially matches an item in
         * the list, Windows selects the item but doesn't send WM_COMMAND with
         * CBN_SELCHANGE. The fix is to detect that the selection has changed
         * and issue the notification.
         */
        int oldSelection = (int) /* 64 */OS.SendMessage(handle, OS.CB_GETCURSEL, 0, 0);
        LRESULT result = super.wmSysKeyDown(hwnd, wParam, lParam);
        if (result != null)
            return result;
        if ((style & SWT.READ_ONLY) == 0)
        {
            if (wParam == OS.VK_DOWN)
            {
                int /* long */code = callWindowProc(hwnd, OS.WM_SYSKEYDOWN, wParam, lParam);
                int newSelection = (int) /* 64 */OS.SendMessage(handle, OS.CB_GETCURSEL, 0, 0);
                if (oldSelection != newSelection)
                {
                    sendEvent(SWT.Modify);
                    if (isDisposed())
                        return LRESULT.ZERO;
                    sendEvent(SWT.Selection);
                    if (isDisposed())
                        return LRESULT.ZERO;
                }
                return new LRESULT(code);
            }
        }
        return result;
    }

}
