using System.Windows;
using System.Windows.Threading;

namespace LivingWorld;

public partial class App : Application
{
    public App()
    {
        // A launcher must never die with a raw WPF crash dialog. Turn any
        // unhandled UI-thread exception into a readable message and keep going
        // where we can, so a failed news fetch or a bad path never kills boot.
        DispatcherUnhandledException += OnDispatcherUnhandledException;
    }

    private void OnDispatcherUnhandledException(object sender, DispatcherUnhandledExceptionEventArgs e)
    {
        MessageBox.Show(
            e.Exception.Message,
            "Living World Launcher",
            MessageBoxButton.OK,
            MessageBoxImage.Warning);
        e.Handled = true;
    }
}
